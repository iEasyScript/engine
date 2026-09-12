package org.projectx.tools.cs2

import java.util.EnumSet
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.cs2.Cs2Cache
import world.gregs.voidps.cache.cs2.Cs2Operand
import world.gregs.voidps.cache.cs2.Cs2PushTag

/**
 * Solves every opcode's operand encoding from the clientscript corpus alone.
 *
 * RS3 renumbers opcodes on every build, so a table solved for one cache says
 * nothing about the next. What does not change is the acceptance test: a parse
 * of a script is valid only when the instruction stream consumes exactly the
 * bytes between the name and the footer *and* yields exactly the instruction
 * count the footer declares.
 *
 * Nothing is inferred from a position the parse could have skipped. A position
 * only votes when every valid parse of that script passes through it - which is
 * true precisely when no valid transition jumps over it - so a candidate set can
 * only ever shrink towards the truth, never away from it.
 */
object Cs2Calibrator {

    /**
     * A bare string operand fits almost anywhere, so admitting it up front makes
     * every script ambiguous. It is only offered if the corpus cannot be parsed
     * without it.
     */
    private val alphabets: List<Set<Cs2Operand>> = listOf(
        EnumSet.of(Cs2Operand.BYTE, Cs2Operand.TRIBYTE, Cs2Operand.INT, Cs2Operand.TAGGED),
        EnumSet.of(Cs2Operand.BYTE, Cs2Operand.TRIBYTE, Cs2Operand.INT, Cs2Operand.TAGGED, Cs2Operand.STRING),
    )

    /** Per-script search frontier, widened round by round as the table fills in. */
    private val caps = listOf(4_000, 16_000, 64_000, 64_000, 256_000, 256_000, 1_000_000, 1_000_000, 4_000_000)

    private const val OPCODE_SPACE = 1 shl 16
    private const val MIN_INSTRUCTION_BYTES = 3
    private const val MAX_SOLUTIONS = 4096
    private const val SHAVE_CAP = 400_000
    private const val SHAVE_PASSES = 4

    /** The language has one tagged push and two tribyte opcodes; no third exists. */
    private val QUOTAS = mapOf(Cs2Operand.TAGGED to 1, Cs2Operand.TRIBYTE to 2)
    private const val WITNESSES_PER_OPCODE = 96

    data class Result(
        val candidates: Map<Int, Set<Cs2Operand>>,
        val occurrences: Map<Int, Int>,
        val unparsed: List<Int>,
        val switchCandidates: Set<Int>,
        val cardinalityForced: Map<Int, Cs2Operand>,
    ) {
        val solved: Map<Int, Cs2Operand>
            get() = candidates.filterValues { it.size == 1 }.mapValues { it.value.first() }

        val ambiguous: Map<Int, Set<Cs2Operand>>
            get() = candidates.filterValues { it.size > 1 }
    }

    fun calibrate(cache: Cache, log: (String) -> Unit = {}): Result {
        val scripts = Cs2Cache(cache)
        val raw = ArrayList<Pair<Int, ByteArray>>()
        for (id in scripts.scriptIds()) {
            val bytes = try {
                scripts.raw(id) ?: continue
            } catch (e: Exception) {
                continue
            }
            raw.add(id to bytes)
        }
        return calibrate(raw, log)
    }

    /**
     * Solves a corpus handed over as raw files rather than read from a cache, so
     * a build that is not installed anywhere - including a synthetic one - is
     * solved by exactly the same code.
     */
    fun calibrate(rawScripts: Iterable<Pair<Int, ByteArray>>, log: (String) -> Unit = {}): Result {
        val corpus = Corpus.of(rawScripts)
        log("${corpus.scripts.size} scripts, ${corpus.scripts.sumOf { it.body }} instruction bytes")
        var result = solve(corpus, alphabets.first(), log)
        for (alphabet in alphabets.drop(1)) {
            if (result.unparsed.isEmpty()) break
            log("${result.unparsed.size} scripts unparsed - retrying with ${alphabet.joinToString("|")}")
            result = solve(corpus, alphabet, log)
        }
        return result
    }

    private fun solve(corpus: Corpus, alphabet: Set<Cs2Operand>, log: (String) -> Unit): Result {
        val candidates = arrayOfNulls<EnumSet<Cs2Operand>>(OPCODE_SPACE)
        val contradictions = HashSet<Int>()
        var unparsed = emptyList<Int>()

        var cardinalityForced = emptyMap<Int, Cs2Operand>()
        for (round in caps.indices) {
            cardinalityForced = cardinalityForced + applyGlobalCardinality(candidates)
            val roundAlphabet = alphabet - filledQuotas(candidates)
            var changed = 0
            var forced = 0
            var overflowed = 0
            val stillUnparsed = ArrayList<Int>()

            var settled = 0
            for (script in corpus.scripts) {
                if (script.determined(candidates)) {
                    settled++
                    continue
                }
                val votes = script.vote(candidates, roundAlphabet, caps[round])
                if (votes == null) {
                    overflowed++
                    continue
                }
                if (votes.isEmpty()) {
                    stillUnparsed.add(script.id)
                    continue
                }
                if (script.singleParse) forced++
                corpus.recordWitnesses(script)
                for ((opcode, widths) in votes) {
                    val existing = candidates[opcode]
                    if (existing == null) {
                        candidates[opcode] = widths
                        changed++
                    } else if (existing.retainAll(widths)) {
                        if (existing.isEmpty()) {
                            existing.addAll(widths)
                            contradictions.add(opcode)
                        }
                        changed++
                    }
                }
            }

            unparsed = stillUnparsed
            val seen = candidates.count { it != null }
            val pinned = candidates.count { it != null && it.size == 1 }
            log(
                "round $round: seen=$seen pinned=$pinned settled=$settled forcedParses=$forced " +
                    "unsolved=${stillUnparsed.size} overflow=$overflowed changed=$changed",
            )
            if (changed == 0 && round > 0 && caps[round] == caps[round - 1]) break
        }
        if (contradictions.isNotEmpty()) {
            log("contradicting opcodes (no width satisfies every script): ${contradictions.sorted()}")
        }

        shave(corpus, candidates, alphabet - filledQuotas(candidates), log)
        cardinalityForced = cardinalityForced + applyGlobalCardinality(candidates)
        val table = candidates.toMap()
        return Result(
            candidates = table,
            occurrences = corpus.occurrences(candidates, alphabet),
            unparsed = unparsed,
            switchCandidates = corpus.switchCandidates(candidates),
            cardinalityForced = cardinalityForced,
        )
    }

    /**
     * Refutes a width by assuming it and propagating until something breaks.
     *
     * Propagation on its own only learns from positions a parse is forced
     * through, and the widths it cannot separate are separated by nothing weaker:
     * they trade bytes against each other, so a script admits several assignments
     * and no single position is decisive. Assuming one width and pushing the
     * consequences through the scripts that use the opcode breaks that tie - if
     * some script is then left with no parse, or some other opcode with no width,
     * the assumption was wrong.
     */
    private fun shave(
        corpus: Corpus,
        candidates: Array<EnumSet<Cs2Operand>?>,
        alphabet: Set<Cs2Operand>,
        log: (String) -> Unit,
    ) {
        var pass = 0
        while (true) {
            var refuted = 0
            for (opcode in candidates.indices) {
                val widths = candidates[opcode] ?: continue
                if (widths.size < 2 || corpus.witnesses(opcode).isEmpty()) continue
                for (width in widths.toList()) {
                    if (widths.size < 2) break
                    if (contradicts(corpus, candidates, alphabet, opcode, width)) {
                        widths.remove(width)
                        refuted++
                    }
                }
            }
            pass++
            val pinned = candidates.count { it != null && it.size == 1 }
            log("shave $pass: refuted=$refuted pinned=$pinned")
            if (refuted == 0) break
        }
    }

    private fun contradicts(
        corpus: Corpus,
        candidates: Array<EnumSet<Cs2Operand>?>,
        alphabet: Set<Cs2Operand>,
        opcode: Int,
        width: Cs2Operand,
    ): Boolean {
        val scratch = arrayOfNulls<EnumSet<Cs2Operand>>(OPCODE_SPACE)
        for (id in candidates.indices) candidates[id]?.let { scratch[id] = EnumSet.copyOf(it) }
        scratch[opcode] = EnumSet.of(width)

        repeat(SHAVE_PASSES) {
            for (script in corpus.witnesses(opcode)) {
                val votes = script.vote(scratch, alphabet, SHAVE_CAP) ?: continue
                if (votes.isEmpty()) return true
                for ((seen, widths) in votes) {
                    val domain = scratch[seen]
                    if (domain == null) {
                        scratch[seen] = widths
                    } else if (domain.retainAll(widths) && domain.isEmpty()) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /** Once the corpus has pinned a quota's worth, no other opcode can be one. */
    private fun applyGlobalCardinality(candidates: Array<EnumSet<Cs2Operand>?>): Map<Int, Cs2Operand> {
        val filled = filledQuotas(candidates)
        val forced = HashMap<Int, Cs2Operand>()
        for (id in candidates.indices) {
            val set = candidates[id] ?: continue
            if (set.size <= 1 || !set.removeAll(filled)) continue
            if (set.size == 1) forced[id] = set.first()
        }
        return forced
    }

    private fun filledQuotas(candidates: Array<EnumSet<Cs2Operand>?>): EnumSet<Cs2Operand> {
        val filled = EnumSet.noneOf(Cs2Operand::class.java)
        for ((operand, quota) in QUOTAS) {
            val pinned = candidates.count { it != null && it.size == 1 && it.first() == operand }
            if (pinned >= quota) filled.add(operand)
        }
        return filled
    }

    private fun Array<EnumSet<Cs2Operand>?>.toMap(): Map<Int, Set<Cs2Operand>> {
        val out = LinkedHashMap<Int, Set<Cs2Operand>>()
        for (id in indices) this[id]?.let { out[id] = it }
        return out
    }

    private class Corpus(val scripts: List<Script>) {

        private val byOpcode = HashMap<Int, LinkedHashSet<Script>>()

        fun recordWitnesses(script: Script) {
            for (opcode in script.liveOpcodes) {
                val seen = byOpcode.getOrPut(opcode) { LinkedHashSet() }
                if (seen.size < WITNESSES_PER_OPCODE) seen.add(script)
            }
        }

        fun witnesses(opcode: Int): Collection<Script> = byOpcode[opcode] ?: emptyList()

        /** Scripts each opcode appears in, which stays meaningful while it is ambiguous. */
        fun occurrences(candidates: Array<EnumSet<Cs2Operand>?>, alphabet: Set<Cs2Operand>): Map<Int, Int> {
            val counts = HashMap<Int, Int>()
            for (script in scripts) {
                script.parses(candidates, alphabet, SHAVE_CAP)
                script.countOccurrences(counts)
            }
            return counts
        }

        /** Opcodes whose operand always indexes the footer's switch-table array. */
        fun switchCandidates(candidates: Array<EnumSet<Cs2Operand>?>): Set<Int> {
            val plausible = HashSet<Int>()
            val ruledOut = HashSet<Int>()
            for (script in scripts) script.classifySwitchOperands(candidates, plausible, ruledOut)
            return plausible - ruledOut
        }

        companion object {
            fun of(rawScripts: Iterable<Pair<Int, ByteArray>>): Corpus {
                val scripts = ArrayList<Script>()
                for ((id, raw) in rawScripts) scripts.add(Script.of(id, raw) ?: continue)
                scripts.sortWith(compareBy({ it.instructionCount }, { it.body }))
                return Corpus(scripts)
            }
        }
    }

    private class Script(
        val id: Int,
        val raw: ByteArray,
        val start: Int,
        val end: Int,
        val instructionCount: Int,
        val switchTableCount: Int,
    ) {
        val body: Int get() = end - start

        /** True when every opcode in the one parse the solved widths allow is already pinned. */
        fun determined(candidates: Array<EnumSet<Cs2Operand>?>): Boolean {
            var at = start
            var count = 0
            while (at != end) {
                if (at + 2 > end) return false
                val operand = candidates[opcodeAt(at)]?.singleOrNull() ?: return false
                at = advance(at, operand)
                if (at < 0) return false
                count++
            }
            return count == instructionCount
        }

        /** True when the last vote found exactly one parse of this script. */
        var singleParse = false
            private set

        /** Opcodes reached at a position some valid parse crosses, from the last vote. */
        val liveOpcodes = HashSet<Int>()

        /** True when the last search hit its frontier rather than finishing. */
        var overflowed = false
            private set

        fun opcodeAt(at: Int): Int = ((raw[at].toInt() and 0xFF) shl 8) or (raw[at + 1].toInt() and 0xFF)

        fun advance(at: Int, operand: Cs2Operand): Int {
            val next = when (operand) {
                Cs2Operand.BYTE -> at + 3
                Cs2Operand.TRIBYTE -> at + 5
                Cs2Operand.INT, Cs2Operand.VAR, Cs2Operand.WIDE_VARBIT -> at + 6
                Cs2Operand.LONG -> at + 10
                Cs2Operand.STRING -> terminator(at + 2)
                Cs2Operand.TAGGED -> when (raw[at + 2].toInt() and 0xFF) {
                    Cs2PushTag.INT -> at + 7
                    Cs2PushTag.LONG -> at + 11
                    Cs2PushTag.STRING -> terminator(at + 3)
                    else -> -1
                }
            }
            return if (next in 0..end) next else -1
        }

        private fun terminator(from: Int): Int {
            var i = from
            while (i < end && raw[i] != 0.toByte()) i++
            return if (i < end) i + 1 else -1
        }

        private fun widths(
            candidates: Array<EnumSet<Cs2Operand>?>,
            alphabet: Set<Cs2Operand>,
            opcode: Int,
        ): Set<Cs2Operand> = candidates[opcode] ?: alphabet

        /**
         * Operand widths this script proves possible for each opcode.
         *
         * Two passes, both of which can only ever return a superset of the truth.
         * The first ignores the rule that one opcode keeps one width for the whole
         * script, which makes it a cheap exhaustive reachability sweep; it yields
         * the live width set per byte position and votes from the positions every
         * parse must cross. The second walks only those live widths, this time
         * holding each opcode to a single width, and votes from complete parses.
         *
         * Null when either pass was cut short, since a partial enumeration could
         * miss the true parse and would then rule out the widths it uses.
         */
        fun vote(
            candidates: Array<EnumSet<Cs2Operand>?>,
            alphabet: Set<Cs2Operand>,
            cap: Int,
        ): Map<Int, EnumSet<Cs2Operand>>? {
            singleParse = false
            if (instructionCount == 0) return emptyMap()
            val reach = reachable(candidates, alphabet, cap) ?: return if (overflowed) null else emptyMap()
            if (reach.live[start]?.contains(0) != true) return emptyMap()

            val votes = reach.bridgeVotes()
            val consistent = consistentVotes(reach, cap) ?: return votes
            for ((opcode, widths) in consistent) {
                val known = votes[opcode]
                if (known == null) votes[opcode] = widths else known.retainAll(widths)
            }
            return votes
        }

        private inner class Reachable(
            val live: Array<MutableSet<Int>?>,
            val liveWidths: Array<Array<Cs2Operand>?>,
        ) {
            /**
             * A position is crossed by every parse exactly when no live transition
             * jumps over it, which a running cover count reads straight off.
             */
            fun bridgeVotes(): HashMap<Int, EnumSet<Cs2Operand>> {
                val jumped = IntArray(end + 2)
                for (at in start until end) {
                    for (operand in liveWidths[at] ?: continue) {
                        jumped[at + 1]++
                        jumped[advance(at, operand)]--
                    }
                }
                val votes = HashMap<Int, EnumSet<Cs2Operand>>()
                var cover = 0
                for (at in start until end) {
                    cover += jumped[at]
                    val widths = liveWidths[at] ?: continue
                    if (cover != 0) continue
                    val opcode = opcodeAt(at)
                    val known = votes[opcode]
                    if (known == null) {
                        votes[opcode] = EnumSet.copyOf(widths.asList())
                    } else {
                        known.retainAll(widths.toSet())
                    }
                }
                return votes
            }
        }

        private fun reachable(
            candidates: Array<EnumSet<Cs2Operand>?>,
            alphabet: Set<Cs2Operand>,
            cap: Int,
        ): Reachable? {
            overflowed = false
            liveOpcodes.clear()
            val forward = arrayOfNulls<MutableSet<Int>>(end + 1)
            forward[start] = hashSetOf(0)
            var states = 1
            for (at in start until end) {
                val counts = forward[at] ?: continue
                if (at + 2 > end) continue
                for (operand in candidates[opcodeAt(at)] ?: alphabet) {
                    val next = advance(at, operand)
                    if (next < 0) continue
                    val room = end - next
                    var target = forward[next]
                    for (count in counts) {
                        val reached = count + 1
                        if (reached > instructionCount) continue
                        if (room < MIN_INSTRUCTION_BYTES * (instructionCount - reached)) continue
                        if (target == null) {
                            target = HashSet()
                            forward[next] = target
                        }
                        if (target.add(reached) && ++states > cap) {
                            overflowed = true
                            return null
                        }
                    }
                }
            }
            if (forward[end]?.contains(instructionCount) != true) return null

            val live = arrayOfNulls<MutableSet<Int>>(end + 1)
            val liveWidths = arrayOfNulls<Array<Cs2Operand>>(end + 1)
            live[end] = hashSetOf(instructionCount)
            for (at in end - 1 downTo start) {
                val counts = forward[at] ?: continue
                if (at + 2 > end) continue
                var reaching: MutableSet<Int>? = null
                var widths: MutableList<Cs2Operand>? = null
                for (operand in candidates[opcodeAt(at)] ?: alphabet) {
                    val next = advance(at, operand)
                    if (next < 0) continue
                    val suffix = live[next] ?: continue
                    var used = false
                    for (count in counts) {
                        if (!suffix.contains(count + 1)) continue
                        used = true
                        if (reaching == null) {
                            reaching = HashSet()
                            live[at] = reaching
                        }
                        reaching.add(count)
                    }
                    if (used) {
                        if (widths == null) widths = ArrayList(4)
                        widths.add(operand)
                    }
                }
                if (widths != null) {
                    liveWidths[at] = widths.toTypedArray()
                    liveOpcodes.add(opcodeAt(at))
                }
            }
            return Reachable(live, liveWidths)
        }

        /** Whether one complete parse survives the given widths. Null when cut short. */
        fun parses(candidates: Array<EnumSet<Cs2Operand>?>, alphabet: Set<Cs2Operand>, cap: Int): Boolean? {
            if (instructionCount == 0) return true
            val reach = reachable(candidates, alphabet, cap) ?: return if (overflowed) null else false
            if (reach.live[start]?.contains(0) != true) return false
            consistentVotes(reach, cap, firstOnly = true) ?: return if (overflowed) null else false
            return true
        }

        /** Complete parses that hold every opcode to one width, walking live widths only. */
        private fun consistentVotes(reach: Reachable, cap: Int, firstOnly: Boolean = false): Map<Int, EnumSet<Cs2Operand>>? {
            val depth = instructionCount + 1
            val positions = IntArray(depth)
            val choiceIndex = IntArray(depth)
            val claimed = IntArray(depth)
            val assignment = HashMap<Int, Cs2Operand>()
            val solutions = ArrayList<Map<Int, Cs2Operand>>()

            positions[0] = start
            choiceIndex[0] = 0
            claimed[0] = -1
            var level = 0
            var states = 0

            while (level >= 0) {
                if (claimed[level] >= 0) {
                    assignment.remove(claimed[level])
                    claimed[level] = -1
                }
                val at = positions[level]
                val available = reach.liveWidths[at]!!
                if (choiceIndex[level] >= available.size) {
                    level--
                    continue
                }
                val operand = available[choiceIndex[level]++]
                if (++states > cap) {
                    overflowed = true
                    return null
                }
                val opcode = opcodeAt(at)
                val fixed = assignment[opcode]
                if (fixed != null && fixed != operand) continue
                val next = advance(at, operand)
                if (next < 0 || reach.live[next]?.contains(level + 1) != true) continue
                if (fixed == null) {
                    assignment[opcode] = operand
                    claimed[level] = opcode
                }
                if (next == end) {
                    if (level + 1 == instructionCount) {
                        solutions.add(HashMap(assignment))
                        if (firstOnly) return emptyMap()
                        if (solutions.size >= MAX_SOLUTIONS) {
                            overflowed = true
                            return null
                        }
                    }
                    continue
                }
                if (level + 1 >= instructionCount || reach.liveWidths[next] == null) continue
                level++
                positions[level] = next
                choiceIndex[level] = 0
                claimed[level] = -1
            }

            if (solutions.isEmpty()) return null
            singleParse = solutions.size == 1
            val votes = HashMap<Int, EnumSet<Cs2Operand>>()
            for ((opcode, operand) in solutions.first()) votes[opcode] = EnumSet.of(operand)
            for (solution in solutions.drop(1)) {
                votes.keys.retainAll(solution.keys)
                for ((opcode, operand) in solution) votes[opcode]?.add(operand)
            }
            return votes
        }

        fun countOccurrences(into: HashMap<Int, Int>) {
            for (opcode in liveOpcodes) into[opcode] = (into[opcode] ?: 0) + 1
        }

        fun classifySwitchOperands(
            candidates: Array<EnumSet<Cs2Operand>?>,
            plausible: MutableSet<Int>,
            ruledOut: MutableSet<Int>,
        ) {
            var at = start
            while (at + 2 <= end) {
                val opcode = opcodeAt(at)
                val widths = candidates[opcode] ?: return
                val operand = widths.singleOrNull() ?: return
                if (operand == Cs2Operand.BYTE || operand == Cs2Operand.INT) {
                    val value = if (operand == Cs2Operand.BYTE) {
                        raw[at + 2].toInt() and 0xFF
                    } else {
                        BufferReader(raw).apply { position(at + 2) }.readInt()
                    }
                    if (value in 0 until switchTableCount) plausible.add(opcode) else ruledOut.add(opcode)
                }
                at = advance(at, operand).takeIf { it >= 0 } ?: return
            }
        }

        companion object {
            private const val FOOTER_BYTES = 18

            fun of(id: Int, raw: ByteArray): Script? {
                if (raw.size <= FOOTER_BYTES) return null
                val reader = BufferReader(raw)
                reader.position(raw.size - 2)
                val switchBlockSize = reader.readUnsignedShort()
                val end = raw.size - FOOTER_BYTES - switchBlockSize
                if (end < 1 || end >= raw.size) return null
                reader.position(end)
                val instructionCount = reader.readInt()
                if (instructionCount < 0 || instructionCount * MIN_INSTRUCTION_BYTES > end) return null
                reader.position(end + 16)
                val switchTableCount = reader.readUnsignedByte()
                var start = 0
                while (start < raw.size && raw[start] != 0.toByte()) start++
                start++
                if (start > end) return null
                return Script(id, raw, start, end, instructionCount, switchTableCount)
            }
        }
    }
}
