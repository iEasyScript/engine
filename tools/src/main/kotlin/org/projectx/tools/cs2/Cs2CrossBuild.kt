package org.projectx.tools.cs2

import java.io.File
import java.nio.file.Paths
import kotlin.random.Random
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.cs2.Cs2BuildId
import world.gregs.voidps.cache.cs2.Cs2Codec
import world.gregs.voidps.cache.cs2.Cs2Corpus
import world.gregs.voidps.cache.cs2.Cs2DispatchTableImport
import world.gregs.voidps.cache.cs2.Cs2Op
import world.gregs.voidps.cache.cs2.Cs2OpcodeEntry
import world.gregs.voidps.cache.cs2.Cs2OpcodeTable
import world.gregs.voidps.cache.cs2.Cs2Opcodes
import world.gregs.voidps.cache.cs2.Cs2Operand
import world.gregs.voidps.cache.cs2.Cs2RawScript
import world.gregs.voidps.cache.cs2.Cs2TableExport
import world.gregs.voidps.cache.cs2.Cs2Walk
import world.gregs.voidps.cache.cs2.Cs2Walked
import world.gregs.voidps.cache.cs2.OpKind
import world.gregs.voidps.cache.sqlite.SQLiteCache

/**
 * The commands that take one build's opcode table out of the working tree and
 * onto the next build.
 *
 * Three of them stand behind the fourth. `export-table` writes the anchor,
 * `coldstart` says how much of that anchor the corpus could rebuild with nothing
 * installed, and `rehearse` runs the whole cross-build derivation against a
 * renumbering it generated itself, so the answer is known before a real build
 * arrives. `unscramble` is the one that runs on update day.
 */
object Cs2CrossBuild {

    fun exportTable(cache: Cache, build: String?) {
        val id = build ?: Cs2BuildId.current()
        if (id == null) {
            println("No build id: pass one, or restore client-plugin-engine/src/main/resources/offsets/index.json")
            return
        }
        val entries = Cs2OpcodeTable.load(cache)
        if (entries == null) {
            println("No opcode table for this cache - run `cs2 import` or `cs2 calibrate` first.")
            return
        }
        val corpus = Cs2Corpus.of(cache, id)
        val evidence = Cs2TableExport.Evidence.read(File("data/cs2"))
        val written = Cs2TableExport.write(
            build = id,
            corpus = corpus,
            entries = entries,
            unresolvedWidths = Cs2OpcodeTable.unresolved(cache),
            evidence = evidence,
            stamp = Cs2TableExport.Stamp(
                command = "cs2 export-table",
                installedTable = Cs2OpcodeTable.file(cache).takeIf { it.isFile },
                installedSource = Cs2OpcodeTable.source(cache),
            ),
        )
        println("build:      $id")
        println("cache:      index-12 ref table format ${corpus.format} revision ${corpus.revision}, crc ${corpus.indexCrc}, ${corpus.scripts.size} scripts")
        val (checked, matched) = corpus.checkNameHashes()
        println("name hashes: ${corpus.scripts.count { it.nameHash != 0 }} carried, ${corpus.byNameHash.size} unique, $matched/$checked hash Jagex's own name")
        println("opcodes:    ${entries.size}")
        entries.groupingBy { it.naming.origin }.eachCount().entries.sortedBy { it.key }
            .forEach { (origin, count) -> println("  %-12s %5d".format(origin.name.lowercase(), count)) }
        val widths = entries.associate { it.id to it.operand }
        val unnamed = entries.filter { it.naming.isEmpty }.mapTo(HashSet()) { it.id }
        var sites = 0
        var unnamedSites = 0
        val unnamedUsed = HashSet<Int>()
        for (script in corpus.scripts) {
            val walked = Cs2Walk.walk(script.id, script.bytes, widths) ?: continue
            sites += walked.size
            for (opcode in walked.opcodes) {
                if (opcode in unnamed) {
                    unnamedSites++
                    unnamedUsed.add(opcode)
                }
            }
        }
        println("call sites: $sites, of which $unnamedSites reach one of ${unnamedUsed.size} unnamed opcodes (%.4f%%)".format(unnamedSites * 100.0 / sites))
        println("evidence:   ${evidence.sources.joinToString(", ") { it.name }}")
        println("wrote ${written.absolutePath} (${written.length()} bytes)")
    }

    /**
     * Re-solves this build's operand widths with nothing installed, then says
     * how much of the table that alone reproduces.
     *
     * This is the question the whole cross-build story rests on, because on the
     * day a new build lands the corpus solve is the only width source that does
     * not need a binary. Anything it cannot pin has to come from somewhere else,
     * and it is worth far more to know exactly what that is now than to find out
     * on the day.
     */
    fun coldStart(cache: Cache) {
        val installed = Cs2OpcodeTable.load(cache)
        if (installed == null) {
            println("No opcode table for this cache to compare against.")
            return
        }
        Cs2Opcodes.restoreLegacy()
        println("Re-solving operand widths from the corpus alone...")
        val started = System.currentTimeMillis()
        val result = Cs2Calibrator.calibrate(cache) { println("  $it") }
        println("elapsed: ${System.currentTimeMillis() - started}ms")

        val known = installed.associate { it.id to it.operand }
        val check = Cs2Unscrambler.crossCheckWidths(result.solved, result.ambiguous, known)
        reportWidths(check)

        val corpus = Cs2Corpus.of(cache, "current")
        val used = corpus.scripts.mapNotNull { Cs2Walk.walk(it.id, it.bytes, known) }
            .flatMap { it.opcodes.asIterable() }.toSet()
        println()
        val pinned = result.solved.keys.count { it in used }
        val open = result.ambiguous.keys.count { it in used }
        println("opcodes the corpus actually uses: ${used.size} of ${installed.size} in the table")
        println("  re-solved outright:             $pinned")
        println("  left with two readings:         $open")
        println("  the solve never reached:        ${used.size - pinned - open}")
        println("  never reached by a script:      ${installed.size - used.size}")

        reportNarrowestDefault(corpus, result, known)

        val hinted = known + result.solved
        installWidths(hinted)
        val roundTrip = roundTrip(corpus)
        println()
        println("round-trip under the re-solved widths, with the table filling the gaps:")
        println("  ${roundTrip.identical}/${roundTrip.scripts} byte-identical, ${roundTrip.failed} failed")

        if (check.corpusAmbiguous.isEmpty()) {
            println()
            println("COLD START: the corpus pins every width it sees; no hint was needed.")
            return
        }
        println()
        println("COLD START: the corpus does NOT converge on its own.")
        println("  hint needed: the operand width of ${check.corpusAmbiguous.size} opcodes the corpus narrows")
        println("  to ${check.corpusAmbiguous.values.flatten().distinct().sorted().joinToString("|")} and no further, plus ${used.size - pinned - open} its search never reaches at all.")
        println("  the client's dispatch table settles all of them; nothing else in this toolchain does.")
        println("  what the corpus DOES settle it settles correctly: ${check.disagreed.size} of ${check.agreed.size + check.indistinguishable.size} disagree with the binary.")
        val byTruth = check.corpusAmbiguous.keys.groupingBy { known[it] }.eachCount()
        println("  the installed table calls them: $byTruth")
        Cs2OpcodeTable.install(installed)
    }

    /**
     * What guessing the narrowest of the two readings would cost.
     *
     * The corpus leaves these opcodes between a one-byte and a four-byte
     * operand. Taking the short one everywhere is the obvious guess, and how
     * much of the corpus still walks under it says how close the solve came - a
     * guess that walks every script is nearly right, one that strands thousands
     * is not a guess worth making.
     */
    private fun reportNarrowestDefault(
        corpus: Cs2Corpus,
        result: Cs2Calibrator.Result,
        known: Map<Int, Cs2Operand>,
    ) {
        if (result.ambiguous.isEmpty()) return
        val narrowest = result.solved + result.ambiguous.keys.associateWith { Cs2Operand.BYTE }
        val walked = corpus.scripts.count { Cs2Walk.walk(it.id, it.bytes, narrowest) != null }
        val wrong = result.ambiguous.keys.count { known[it] != Cs2Operand.BYTE }
        println()
        println("guessing the narrower reading for all ${result.ambiguous.size} of them:")
        println("  scripts that still walk: $walked of ${corpus.scripts.size}")
        println("  opcodes the guess gets wrong, by the installed table: $wrong")
    }

    private fun reportWidths(check: Cs2Unscrambler.WidthCheck) {
        println()
        println("width sources:")
        println("  corpus solve pinned:      ${check.agreed.size + check.disagreed.size + check.indistinguishable.size + check.corpusOnly.size}")
        println("  agreeing with the binary: ${check.agreed.size}")
        println("  same-length encodings:    ${check.indistinguishable.size} (the file cannot tell these apart)")
        println("  the corpus alone:         ${check.corpusOnly.size}")
        println("  the binary alone:         ${check.binaryOnly.size}")
        println("  left ambiguous:           ${check.corpusAmbiguous.size}")
        if (check.disagreed.isEmpty()) {
            println("  DISAGREEING:              0")
            return
        }
        println()
        println("  *** ${check.disagreed.size} OPCODES DISAGREE - one of the two sources is wrong ***")
        println("  *** a build that changed the width rule looks exactly like this ***")
        check.disagreed.toSortedMap().forEach { (id, readings) ->
            println("      $id: corpus says ${readings.first}, binary says ${readings.second}")
        }
    }

    /**
     * Derives the new build's numbering from this one and writes both the map
     * and the new build's table.
     */
    fun unscramble(cache: Cache, args: List<String>) {
        val toCache = args.flag("--to")
        if (toCache == null) {
            println("usage: cs2 unscramble --to <new cache dir> [--to-build <id>] [--dispatch <csv>]")
            println("       [--from-table re-resources/cs2/opcodes-<build>.json]")
            println("  --cache still points at the OLD build's cache: both disassemblies are needed.")
            return
        }
        val committed = args.flag("--from-table")?.let { Cs2TableExport.read(File(it)) }
        val fromEntries = committed?.second ?: Cs2OpcodeTable.load(cache)
        if (fromEntries == null) {
            println("No opcode table to carry forward - pass --from-table, or run `cs2 import` first.")
            return
        }
        val fromBuild = args.flag("--from-build") ?: committed?.first ?: Cs2BuildId.current() ?: "unknown"
        val toBuild = args.flag("--to-build") ?: "next"
        val from = Cs2Corpus.of(cache, fromBuild)
        val target = SQLiteCache.load(Paths.get(toCache).toAbsolutePath().normalize(), readOnly = true)
        try {
            val to = Cs2Corpus.of(target, toBuild)
            val binary = args.flag("--dispatch")?.let { path ->
                Cs2DispatchTableImport.read(File(path)).associate { it.id to it.operand }
            }
            val derived = run(fromBuild, toBuild, from, to, fromEntries, binary, "cs2 unscramble") ?: return
            val evidence = Cs2TableExport.Evidence.read(File("data/cs2"))
                .carriedThrough(derived.mapping.confirmed)
            val written = Cs2TableExport.write(
                build = toBuild,
                corpus = to,
                entries = derived.carried,
                unresolvedWidths = derived.widths.corpusAmbiguous.keys.associateWith { 0 },
                evidence = evidence,
                stamp = Cs2TableExport.Stamp(
                    command = "cs2 unscramble --to-build $toBuild (carried from $fromBuild)",
                    installedTable = null,
                    installedSource = "unscrambled:$fromBuild",
                ),
            )
            println("wrote ${written.absolutePath}")
        } finally {
            target.close()
        }
    }

    /**
     * Runs the whole derivation against a renumbering generated here, so its
     * answer can be checked against a permutation nobody had to guess at.
     *
     * With `--churn` a share of the scripts are edited as well, which is what
     * the corpus of a real build looks like: the pairs that changed have to be
     * thrown out rather than allowed to vote, and the opcodes that only survive
     * in them have to be recovered some other way.
     */
    fun rehearse(cache: Cache, args: List<String>) {
        val entries = Cs2OpcodeTable.load(cache)
        if (entries == null) {
            println("No opcode table for this cache to permute.")
            return
        }
        val seed = args.flag("--seed")?.toLongOrNull() ?: 20260823L
        val churn = args.flag("--churn")?.toDoubleOrNull() ?: 0.0
        val random = Random(seed)
        val widths = entries.associate { it.id to it.operand }
        val from = Cs2Corpus.of(cache, "current")

        val permutation = bijection(widths.keys, random)
        val synthetic = permute(from, widths, permutation, churn, random)
        println("synthetic build: ${synthetic.scripts.size} scripts, seed $seed, churn ${"%.2f".format(churn * 100)}%")

        val truth = permutation.entries.associate { (old, new) -> new to old }
        val binary = widths.entries.associate { (id, operand) -> permutation.getValue(id) to operand }
        val build = Cs2BuildId.current() ?: "current"
        val mapping = run(
            fromBuild = build,
            toBuild = "$build-rehearsal-churn${"%.1f".format(churn * 100)}",
            from = from,
            to = synthetic,
            fromEntries = entries,
            binary = binary,
            command = "cs2 rehearse --seed $seed --churn $churn",
            synthetic = true,
        )?.mapping ?: return

        val reachable = truth.keys.filter { it in mapping.newOpcodes }
        val correct = mapping.confirmed.count { (newId, oldId) -> truth[newId] == oldId }
        val wrong = mapping.confirmed.filter { (newId, oldId) -> truth[newId] != oldId }
        val missed = reachable.filter { it !in mapping.confirmed }
        println()
        println("REHEARSAL against the known permutation:")
        println("  opcodes reachable in the synthetic corpus: ${reachable.size}")
        println("  recovered correctly:                       $correct")
        println("  recovered wrongly:                         ${wrong.size}")
        println("  not recovered:                             ${missed.size}")
        println("  recovery rate: %.3f%%".format(correct * 100.0 / reachable.size.coerceAtLeast(1)))
        if (wrong.isNotEmpty()) {
            println("  wrong: " + wrong.entries.take(20).joinToString { "${it.key}->${it.value} (truth ${truth[it.key]})" })
        }
        if (missed.isNotEmpty()) {
            val floor = missed.groupingBy { mapping.sites[it] ?: 0 }.eachCount().toSortedMap()
            println("  the ones left over, by how many scripts witness them: $floor")
        }
    }

    class Derived(
        val mapping: Cs2Unscrambler.Mapping,
        val carried: List<Cs2OpcodeEntry>,
        val widths: Cs2Unscrambler.WidthCheck,
    )

    private fun run(
        fromBuild: String,
        toBuild: String,
        from: Cs2Corpus,
        to: Cs2Corpus,
        fromEntries: List<Cs2OpcodeEntry>,
        binary: Map<Int, Cs2Operand>?,
        command: String,
        synthetic: Boolean = false,
    ): Derived? {
        println()
        println("Solving the new build's operand widths from its corpus alone...")
        val solve = Cs2Calibrator.calibrate(to.rawScripts()) { println("  $it") }
        val check = Cs2Unscrambler.crossCheckWidths(solve.solved, solve.ambiguous, binary)
        reportWidths(check)
        if (!check.sound) {
            println()
            println("Refusing to derive a mapping while the two width sources disagree.")
            return null
        }
        if (!check.binaryAvailable) {
            println()
            println("*** only one width source was available - the corpus solve is unchecked ***")
            println("*** a build that changed the width rule would still look self-consistent here ***")
        }

        val mapping = Cs2Unscrambler.derive(
            old = Cs2Unscrambler.Build(from, fromEntries.associate { it.id to it.operand }),
            new = Cs2Unscrambler.Build(to, check.widths),
        ) { println("  $it") }

        val carried = Cs2Unscrambler.transfer(fromEntries, mapping.confirmed, check.widths)
        installWidths(check.widths)
        val roundTrip = roundTrip(to)

        println()
        println("mapping:")
        println("  scripts matched by name hash: ${mapping.matchedPairs}")
        println("  pairs aligning 1:1:           ${mapping.alignedPairs}")
        println("  pairs dropped as contradicting: ${mapping.poisonedPairs}")
        println("  pairs the build edited:       ${mapping.changedPairs}")
        println("  opcodes mapped:               ${mapping.confirmed.size} of ${mapping.newOpcodes.size} reached by a script")
        println("    of those, by the second pass: ${mapping.fromSecondPass.size}")
        println("    on a single witness:          ${mapping.singleWitness.size}")
        println("  contested, so left unmapped:  ${mapping.contested.size}")
        println("  new and unexplained:          ${mapping.added.size} (${mapping.suggested.size} with a lead from the second pass)")
        println("  gone from the old build:      ${mapping.removed.size}")
        println("  old opcodes no script reaches: ${fromEntries.count { it.id !in mapping.oldOpcodes }} (nothing here can speak for these)")
        println("  injective:                    ${mapping.injective}")
        println("  round-trip on the new corpus: ${roundTrip.identical}/${roundTrip.scripts}, ${roundTrip.failed} failed")

        val witnessBands = mapping.confirmed.keys.groupingBy { band(mapping.witnesses[it] ?: 0) }.eachCount()
        println("  witnesses per mapping: " + witnessBands.toSortedMap(compareBy { it.first }).entries
            .joinToString { "${it.key.second}=${it.value}" })

        if (!mapping.injective) println("  *** NOT INJECTIVE - two new opcodes claim one old opcode ***")
        if (!roundTrip.ok) println("  *** the new corpus does not round-trip under these widths ***")

        val occurrences = HashMap<Int, Int>()
        for (script in to.scripts) {
            Cs2Walk.walk(script.id, script.bytes, check.widths)?.opcodes?.toSet()
                ?.forEach { occurrences.merge(it, 1, Int::plus) }
        }
        val written = Cs2MapExport.write(
            fromBuild, toBuild, from, to, check, mapping, carried, fromEntries, roundTrip, occurrences,
            command, synthetic,
        )
        println("wrote ${written.absolutePath}")
        return Derived(mapping, carried, check)
    }

    private fun band(votes: Int): Pair<Int, String> = when {
        votes >= 100 -> 100 to "100+"
        votes >= 10 -> 10 to "10-99"
        votes >= 2 -> 2 to "2-9"
        else -> 1 to "1"
    }

    /** A width table is all the codec needs; the names are irrelevant to byte identity. */
    private fun installWidths(widths: Map<Int, Cs2Operand>) {
        Cs2Opcodes.install(
            widths.entries.sortedBy { it.key }.map { (id, operand) ->
                Cs2Op(
                    id = id,
                    opName = "OP$id",
                    tsName = "op$id",
                    largeOperand = operand == Cs2Operand.INT,
                    kind = OpKind.NORMAL,
                    popInt = 0, popStr = 0, popLong = 0, pushInt = 0, pushStr = 0, pushLong = 0,
                    hasTriggerArray = false,
                    operand = operand,
                )
            },
        )
    }

    private fun roundTrip(corpus: Cs2Corpus): Cs2MapExport.RoundTrip {
        var identical = 0
        var failed = 0
        for (script in corpus.scripts) {
            try {
                val decoded = Cs2Codec.decode(script.bytes)
                if (script.bytes.contentEquals(Cs2Codec.encode(decoded))) identical++ else failed++
            } catch (e: Exception) {
                failed++
            }
        }
        return Cs2MapExport.RoundTrip(corpus.scripts.size, identical, failed)
    }

    private fun bijection(ids: Set<Int>, random: Random): Map<Int, Int> {
        val space = (0 until (1 shl 16)).shuffled(random).take(ids.size)
        return ids.sorted().zip(space).toMap()
    }

    /**
     * The same corpus under a different numbering, and optionally with a share of
     * its scripts edited the way a real build edits them.
     *
     * Script ids are shuffled too. Nothing but the name hash should be carrying
     * the match, and leaving the ids alone would let a positional accident stand
     * in for the anchor being tested.
     */
    private fun permute(
        corpus: Cs2Corpus,
        widths: Map<Int, Cs2Operand>,
        permutation: Map<Int, Int>,
        churn: Double,
        random: Random,
    ): Cs2Corpus {
        val ids = corpus.scripts.map { it.id }.shuffled(random)
        val out = ArrayList<Cs2RawScript>(corpus.scripts.size)
        var edited = 0
        for ((index, script) in corpus.scripts.withIndex()) {
            val walked = Cs2Walk.walk(script.id, script.bytes, widths) ?: continue
            var bytes = Cs2Walk.renumber(script.bytes, walked) { permutation.getValue(it) }
            if (walked.size > 2 && random.nextDouble() < churn) {
                bytes = edit(bytes, walked, random) ?: bytes
                edited++
            }
            out.add(Cs2RawScript(ids[index], script.nameHash, bytes))
        }
        if (churn > 0.0) println("  $edited of ${out.size} scripts edited")
        return Cs2Corpus("permuted", corpus.format, corpus.revision, corpus.indexCrc, out)
    }

    /** Deletes one instruction, leaving a file the walker still accepts. */
    private fun edit(bytes: ByteArray, walked: Cs2Walked, random: Random): ByteArray? {
        val index = random.nextInt(walked.size)
        val at = walked.offsets[index]
        val next = if (index + 1 < walked.size) walked.offsets[index + 1] else walked.end
        val removed = next - at
        if (removed <= 0) return null
        val out = ByteArray(bytes.size - removed)
        bytes.copyInto(out, 0, 0, at)
        bytes.copyInto(out, at, next, bytes.size)
        val countAt = walked.end - removed
        val count = walked.size - 1
        out[countAt] = (count ushr 24).toByte()
        out[countAt + 1] = (count ushr 16).toByte()
        out[countAt + 2] = (count ushr 8).toByte()
        out[countAt + 3] = count.toByte()
        return out
    }

    private fun List<String>.flag(name: String): String? {
        val at = indexOf(name)
        return if (at >= 0 && at + 1 < size) this[at + 1] else null
    }
}
