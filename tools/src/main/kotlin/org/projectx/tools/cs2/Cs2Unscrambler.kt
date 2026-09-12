package org.projectx.tools.cs2

import world.gregs.voidps.cache.cs2.Cs2Corpus
import world.gregs.voidps.cache.cs2.Cs2OpcodeEntry
import world.gregs.voidps.cache.cs2.Cs2Operand
import world.gregs.voidps.cache.cs2.Cs2Walk
import world.gregs.voidps.cache.cs2.Cs2Walked

/**
 * Carries one build's opcode table across a renumbering.
 *
 * RS3 scrambles the opcode ids on every build but rewrites almost none of the
 * scripts, so the corpus itself is the Rosetta stone: the same script, compiled
 * twice, spells the same instruction at the same place under two numbers. Line
 * the two disassemblies up and every position is a vote for one pairing.
 *
 * Two anchors make that alignment trustworthy. The reference table's name hash
 * says which script in the new build *is* which script in the old one, without
 * relying on ids that move. The operand-width sequence says the two
 * disassemblies were read the same way, which is what makes a position-by-
 * position pairing meaningful rather than a coincidence of length.
 */
object Cs2Unscrambler {

    /** A build as the unscrambler needs it: its scripts and how wide each opcode's operand is. */
    class Build(val corpus: Cs2Corpus, val widths: Map<Int, Cs2Operand>)

    /**
     * The two independent readings of one build's operand widths.
     *
     * They are kept apart rather than merged because their disagreement is the
     * only warning the toolchain gets that a build changed the width *rule*
     * rather than just the numbering. A corpus solve would converge on something
     * self-consistent either way; only the client's own dispatch table can say
     * it converged on the wrong thing.
     */
    class WidthCheck(
        val widths: Map<Int, Cs2Operand>,
        val agreed: List<Int>,
        val disagreed: Map<Int, Pair<Cs2Operand, Cs2Operand>>,
        val indistinguishable: List<Int>,
        val corpusOnly: List<Int>,
        val binaryOnly: List<Int>,
        val corpusAmbiguous: Map<Int, Set<Cs2Operand>>,
        val binaryAvailable: Boolean,
    ) {
        val sound: Boolean get() = disagreed.isEmpty()
    }

    /**
     * A width the corpus cannot separate from another because the two spend the
     * same number of bytes. Nothing in the file distinguishes them; only the
     * dispatch table knows which of the two the handler reads.
     */
    private val SAME_LENGTH = setOf(setOf(Cs2Operand.INT, Cs2Operand.VAR))

    fun crossCheckWidths(
        corpus: Map<Int, Cs2Operand>,
        ambiguous: Map<Int, Set<Cs2Operand>>,
        binary: Map<Int, Cs2Operand>?,
    ): WidthCheck {
        val agreed = ArrayList<Int>()
        val disagreed = HashMap<Int, Pair<Cs2Operand, Cs2Operand>>()
        val indistinguishable = ArrayList<Int>()
        val corpusOnly = ArrayList<Int>()
        for ((id, solved) in corpus.toSortedMap()) {
            val declared = binary?.get(id)
            when {
                declared == null -> corpusOnly.add(id)
                declared == solved -> agreed.add(id)
                setOf(declared, solved) in SAME_LENGTH -> indistinguishable.add(id)
                else -> disagreed[id] = solved to declared
            }
        }
        val widths = when (binary) {
            null -> corpus
            else -> binary + corpus.filterKeys { it !in binary }
        }
        return WidthCheck(
            widths = widths,
            agreed = agreed,
            disagreed = disagreed,
            indistinguishable = indistinguishable,
            corpusOnly = corpusOnly,
            binaryOnly = binary.orEmpty().keys.filter { it !in corpus }.sorted(),
            corpusAmbiguous = ambiguous,
            binaryAvailable = binary != null,
        )
    }

    /** One matched script pair, reduced to the pairings its alignment proposes. */
    private class Aligned(val proposals: Set<Long>, val sites: Map<Int, Int>)

    class Mapping(
        val confirmed: Map<Int, Int>,
        val witnesses: Map<Int, Int>,
        val sites: Map<Int, Int>,
        val fromSecondPass: Set<Int>,
        val suggested: Map<Int, Pair<Int, Int>>,
        val contested: Map<Int, Map<Int, Int>>,
        val newOpcodes: Set<Int>,
        val oldOpcodes: Set<Int>,
        val matchedPairs: Int,
        val alignedPairs: Int,
        val poisonedPairs: Int,
        val changedPairs: Int,
        val unwalkablePairs: Int,
    ) {
        val added: List<Int> get() = (newOpcodes - confirmed.keys).sorted()
        val removed: List<Int> get() = (oldOpcodes - confirmed.values.toSet()).sorted()
        val singleWitness: List<Int> get() = confirmed.keys.filter { witnesses[it] == 1 }.sorted()

        /** Two new ids landing on one old id would mean the alignment lied somewhere. */
        val injective: Boolean get() = confirmed.values.toSet().size == confirmed.size
    }

    /**
     * How many witnessing scripts the second pass needs before it installs a
     * pairing.
     *
     * The first pass asks something stricter than a count: zero contradictions
     * anywhere in the corpus, which one honest script already satisfies and a
     * hundred dishonest ones never do. It reports how many rest on a single
     * script rather than hiding them. The second pass reads pairings out of
     * scripts the build *did* change, where an alignment is a judgement rather
     * than a correspondence, so there a count is asked for as well.
     */
    private const val MIN_WITNESSES = 2

    fun derive(old: Build, new: Build, log: (String) -> Unit = {}): Mapping {
        val matched = new.corpus.byNameHash.keys.intersect(old.corpus.byNameHash.keys)
        log("${matched.size} scripts matched by name hash of ${new.corpus.scripts.size} in the new build")

        val aligned = ArrayList<Aligned>()
        var changed = 0
        var unwalkable = 0
        val changedPairs = ArrayList<Pair<Cs2Walked, Cs2Walked>>()
        val newOpcodes = HashSet<Int>()
        val oldOpcodes = HashSet<Int>()

        for (hash in matched) {
            val newScript = new.corpus.byNameHash.getValue(hash)
            val oldScript = old.corpus.byNameHash.getValue(hash)
            val newWalk = Cs2Walk.walk(newScript.id, newScript.bytes, new.widths)
            val oldWalk = Cs2Walk.walk(oldScript.id, oldScript.bytes, old.widths)
            if (newWalk == null || oldWalk == null) {
                unwalkable++
                continue
            }
            newWalk.opcodes.forEach { newOpcodes.add(it) }
            oldWalk.opcodes.forEach { oldOpcodes.add(it) }
            if (newWalk.size != oldWalk.size || newWalk.widthKey() != oldWalk.widthKey()) {
                changed++
                changedPairs.add(newWalk to oldWalk)
                continue
            }
            val proposals = HashSet<Long>(newWalk.size)
            val sites = HashMap<Int, Int>()
            for (index in newWalk.opcodes.indices) {
                proposals.add(pair(newWalk.opcodes[index], oldWalk.opcodes[index]))
                sites.merge(newWalk.opcodes[index], 1, Int::plus)
            }
            aligned.add(Aligned(proposals, sites))
        }
        log("${aligned.size} pairs align 1:1, $changed differ, $unwalkable could not be walked")

        val live = dropPoisoned(aligned, log)
        val counts = tally(live)
        val sites = siteCounts(live)

        val confirmed = LinkedHashMap<Int, Int>()
        val witnesses = LinkedHashMap<Int, Int>()
        val contested = LinkedHashMap<Int, Map<Int, Int>>()
        for ((newId, options) in counts.toSortedMap()) {
            if (options.size > 1) {
                contested[newId] = options.toSortedMap()
                continue
            }
            val (oldId, votes) = options.entries.first()
            confirmed[newId] = oldId
            witnesses[newId] = votes
        }
        dropAmbiguousTargets(confirmed, witnesses, contested, counts)

        val second = secondPass(changedPairs, confirmed, old.widths, new.widths, log)
        for ((newId, support) in second.confirmed) {
            confirmed[newId] = support.first
            witnesses[newId] = support.second
        }

        return Mapping(
            confirmed = confirmed.toSortedMap().toMap(LinkedHashMap()),
            witnesses = witnesses,
            sites = sites,
            fromSecondPass = second.confirmed.keys,
            suggested = second.suggested,
            contested = contested,
            newOpcodes = newOpcodes,
            oldOpcodes = oldOpcodes,
            matchedPairs = matched.size,
            alignedPairs = live.size,
            poisonedPairs = aligned.size - live.size,
            changedPairs = changed,
            unwalkablePairs = unwalkable,
        )
    }

    /**
     * Discards every pair that disagrees with the corpus as a whole.
     *
     * An edit anywhere in a script slides every instruction after it, so a pair
     * that really did change proposes a long run of wrong pairings rather than
     * one. Letting it vote at all would bury a rare opcode's only honest witness,
     * which is why a pair that contradicts the consensus is dropped whole.
     */
    private fun dropPoisoned(aligned: List<Aligned>, log: (String) -> Unit): List<Aligned> {
        var live = aligned
        repeat(8) { pass ->
            val counts = tally(live)
            val best = counts.mapValues { (_, options) ->
                val ranked = options.entries.sortedByDescending { it.value }
                if (ranked.size > 1 && ranked[0].value == ranked[1].value) null else ranked[0].key
            }
            val kept = live.filter { pair ->
                pair.proposals.all { best[newOf(it)] == oldOf(it) }
            }
            if (kept.size == live.size) return live
            log("poison pass ${pass + 1}: dropped ${live.size - kept.size} contradicting pairs")
            live = kept
        }
        return live
    }

    /**
     * An old opcode two new ones both claim is a pairing nothing has settled, so
     * neither claim survives. Injectivity is the property the whole transfer
     * rests on: a name carried onto two opcodes is a name asserted about one of
     * them without evidence.
     */
    private fun dropAmbiguousTargets(
        confirmed: MutableMap<Int, Int>,
        witnesses: MutableMap<Int, Int>,
        contested: MutableMap<Int, Map<Int, Int>>,
        counts: Map<Int, Map<Int, Int>>,
    ) {
        val claims = confirmed.entries.groupBy({ it.value }, { it.key })
        for ((oldId, claimants) in claims) {
            if (claimants.size == 1) continue
            for (newId in claimants) {
                confirmed.remove(newId)
                witnesses.remove(newId)
                contested[newId] = mapOf(oldId to (counts[newId]?.get(oldId) ?: 0))
            }
        }
    }

    /**
     * Recovers the opcodes that only survive in scripts the build did edit.
     *
     * The pairings already confirmed turn the two disassemblies into sequences
     * over one alphabet, which lets an edit distance line up the parts that did
     * not change. A position where both sides are unpaired and spend the same
     * operand width is a proposal like any other, and is held to the same rule:
     * many witnesses, no contradiction.
     */
    /**
     * [suggested] holds the pairings the alignment offers that fall short of the
     * rule - typically an opcode whose only surviving use is in one edited
     * script. They are reported rather than installed, because the whole point of
     * the unmapped list is to say what still needs a human, and a lead is worth
     * more there than a silent gap.
     */
    private class SecondPass(
        val confirmed: Map<Int, Pair<Int, Int>>,
        val suggested: Map<Int, Pair<Int, Int>>,
    )

    private fun secondPass(
        pairs: List<Pair<Cs2Walked, Cs2Walked>>,
        confirmed: Map<Int, Int>,
        oldWidths: Map<Int, Cs2Operand>,
        newWidths: Map<Int, Cs2Operand>,
        log: (String) -> Unit,
    ): SecondPass {
        val claimed = confirmed.values.toHashSet()
        val proposals = HashMap<Int, HashMap<Int, Int>>()
        var walked = 0
        for ((newWalk, oldWalk) in pairs) {
            if (newWalk.size.toLong() * oldWalk.size > ALIGNMENT_CELLS) continue
            walked++
            for ((newId, oldId) in align(newWalk, oldWalk, confirmed)) {
                if (newId in confirmed || oldId in claimed) continue
                if (newWidths[newId] != oldWidths[oldId]) continue
                proposals.getOrPut(newId) { HashMap() }.merge(oldId, 1, Int::plus)
            }
        }
        val recovered = LinkedHashMap<Int, Pair<Int, Int>>()
        val suggested = LinkedHashMap<Int, Pair<Int, Int>>()
        for ((newId, options) in proposals.toSortedMap()) {
            if (options.size > 1) continue
            val (oldId, votes) = options.entries.first()
            if (votes < MIN_WITNESSES) suggested[newId] = oldId to votes else recovered[newId] = oldId to votes
        }
        val doubleClaimed = recovered.entries.groupBy({ it.value.first }, { it.key })
            .filterValues { it.size > 1 }.values.flatten()
        doubleClaimed.forEach { recovered.remove(it) }
        log(
            "second pass: $walked of ${pairs.size} edited pairs aligned, ${recovered.size} further opcodes " +
                "recovered, ${suggested.size} offered too little support to install",
        )
        return SecondPass(recovered, suggested)
    }

    private const val ALIGNMENT_CELLS = 4_000_000L

    /**
     * Needleman-Wunsch over the two instruction streams, scoring a pair the
     * confirmed mapping already vouches for above one it merely permits.
     */
    private fun align(
        newWalk: Cs2Walked,
        oldWalk: Cs2Walked,
        confirmed: Map<Int, Int>,
    ): List<Pair<Int, Int>> {
        val rows = newWalk.size
        val columns = oldWalk.size
        val score = Array(rows + 1) { IntArray(columns + 1) }
        for (row in 1..rows) score[row][0] = -row
        for (column in 1..columns) score[0][column] = -column
        for (row in 1..rows) {
            for (column in 1..columns) {
                val diagonal = score[row - 1][column - 1] + cost(
                    newWalk.opcodes[row - 1], oldWalk.opcodes[column - 1],
                    newWalk.widths[row - 1], oldWalk.widths[column - 1], confirmed,
                )
                score[row][column] = maxOf(diagonal, score[row - 1][column] - 1, score[row][column - 1] - 1)
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        var row = rows
        var column = columns
        while (row > 0 && column > 0) {
            val diagonal = score[row - 1][column - 1] + cost(
                newWalk.opcodes[row - 1], oldWalk.opcodes[column - 1],
                newWalk.widths[row - 1], oldWalk.widths[column - 1], confirmed,
            )
            when {
                score[row][column] == diagonal -> {
                    out.add(newWalk.opcodes[row - 1] to oldWalk.opcodes[column - 1])
                    row--
                    column--
                }
                score[row][column] == score[row - 1][column] - 1 -> row--
                else -> column--
            }
        }
        return out
    }

    private fun cost(
        newId: Int,
        oldId: Int,
        newWidth: Cs2Operand,
        oldWidth: Cs2Operand,
        confirmed: Map<Int, Int>,
    ): Int {
        val known = confirmed[newId]
        return when {
            known == oldId -> 2
            known != null -> -2
            oldId in confirmed.values -> -2
            newWidth == oldWidth -> 1
            else -> -2
        }
    }

    private fun tally(pairs: List<Aligned>): Map<Int, Map<Int, Int>> {
        val counts = HashMap<Int, HashMap<Int, Int>>()
        for (pair in pairs) {
            for (proposal in pair.proposals) {
                counts.getOrPut(newOf(proposal)) { HashMap() }.merge(oldOf(proposal), 1, Int::plus)
            }
        }
        return counts
    }

    /** Instruction positions each new opcode occupies across the surviving pairs. */
    private fun siteCounts(pairs: List<Aligned>): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (pair in pairs) for ((opcode, seen) in pair.sites) counts.merge(opcode, seen, Int::plus)
        return counts
    }

    private fun pair(newId: Int, oldId: Int): Long = (newId.toLong() shl 32) or (oldId.toLong() and 0xFFFFFFFFL)

    private fun newOf(pair: Long): Int = (pair ushr 32).toInt()

    private fun oldOf(pair: Long): Int = pair.toInt()

    /**
     * Rewrites the old build's table onto the new build's numbering.
     *
     * Nothing is promoted on the way across. A name this toolchain worked out
     * stays this toolchain's, a name matched in from a third build stays matched
     * in, and an opcode the mapping does not reach keeps nothing at all - being
     * carried forward is not evidence.
     */
    fun transfer(
        old: List<Cs2OpcodeEntry>,
        mapping: Map<Int, Int>,
        widths: Map<Int, Cs2Operand>,
    ): List<Cs2OpcodeEntry> {
        val byId = old.associateBy { it.id }
        return widths.keys.sorted().map { newId ->
            val carried = mapping[newId]?.let { byId[it] }
            val operand = widths.getValue(newId)
            carried?.copy(id = newId, operand = operand) ?: Cs2OpcodeEntry(newId, operand)
        }
    }
}
