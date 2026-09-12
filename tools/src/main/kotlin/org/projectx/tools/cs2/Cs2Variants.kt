package org.projectx.tools.cs2

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.cs2.Cs2Analyzer
import world.gregs.voidps.cache.cs2.Cs2Cache
import world.gregs.voidps.cache.cs2.Cs2Confidence
import world.gregs.voidps.cache.cs2.Cs2OpcodeEntry
import world.gregs.voidps.cache.cs2.Cs2OpcodeTable
import world.gregs.voidps.cache.cs2.StackEffect

/**
 * Chooses between the stack effects the handler read left open.
 *
 * Where following a handler runs into an indirect jump the read stops with a set
 * of candidate signatures rather than one, and nothing else in the binary
 * chooses between them. The corpus does: a wrong signature leaves the operand
 * stacks unbalanced in the scripts that use the opcode, so the candidate more of
 * them walk under is the one the client must have.
 *
 * A candidate is only taken when it beats every other outright and the opcode's
 * scripts are actually better off for it. A tie is not evidence, and putting a
 * guess under an opcode the read deliberately left open would be worse than
 * leaving it open.
 */
object Cs2VariantSolver {

    data class Choice(
        val id: Int,
        val effect: StackEffect,
        /** Scripts using this opcode that walk under the chosen candidate. */
        val walked: Int,
        /** ...and under the best of the rest. */
        val runnerUp: Int,
        val scripts: Int,
    )

    fun solve(
        cache: Cache,
        entries: List<Cs2OpcodeEntry>,
        candidates: Map<Int, List<StackEffect>>,
        log: (String) -> Unit = {},
    ): Pair<List<Cs2OpcodeEntry>, List<Choice>> {
        val analyzer = Cs2Analyzer(cache)
        analyzer.analyse()
        val scripts = Cs2Cache(cache)
        val users = usersOf(analyzer, candidates.keys)
        log("${candidates.size} opcodes carry more than one candidate; ${users.values.sumOf { it.size }} uses")

        var table = entries
        val choices = ArrayList<Choice>()
        for (opcode in candidates.keys.sortedByDescending { users[it]?.size ?: 0 }) {
            val ids = users[opcode] ?: continue
            val scored = candidates.getValue(opcode)
                .map { effect ->
                    Cs2OpcodeTable.install(table.replacing(opcode, effect))
                    effect to ids.count { walks(scripts, analyzer, it) }
                }
                .sortedByDescending { it.second }
            Cs2OpcodeTable.install(table)

            val best = scored.first()
            val runnerUp = scored.getOrNull(1)?.second ?: 0
            if (best.second <= runnerUp) continue
            // The read already stands behind this one; corroborating it is not a
            // reason to relabel where it came from.
            if (best.first == table.first { it.id == opcode }.effect) continue
            table = table.replacing(opcode, best.first)
            Cs2OpcodeTable.install(table)
            choices.add(Choice(opcode, best.first, best.second, runnerUp, ids.size))
            log("opcode $opcode: ${best.first.counts.joinToString("/")} walks ${best.second} of ${ids.size} (next best $runnerUp)")
        }
        return table to choices
    }

    private fun List<Cs2OpcodeEntry>.replacing(opcode: Int, effect: StackEffect): List<Cs2OpcodeEntry> =
        map { if (it.id == opcode) it.copy(effect = effect, confidence = Cs2Confidence.CORPUS_CHOSEN) else it }

    /** Script ids using each of [opcodes]. */
    private fun usersOf(analyzer: Cs2Analyzer, opcodes: Set<Int>): Map<Int, List<Int>> {
        val out = HashMap<Int, MutableList<Int>>()
        for (id in analyzer.ids) {
            val script = analyzer.script(id) ?: continue
            script.instructions.mapTo(HashSet()) { it.op.id }
                .filter { it in opcodes }
                .forEach { out.getOrPut(it) { ArrayList() }.add(id) }
        }
        return out
    }

    /**
     * Scripts are re-decoded rather than reused: an instruction holds the opcode
     * it was decoded against, so one decoded under a different candidate would
     * still be walked with that candidate's counts.
     */
    private fun walks(scripts: Cs2Cache, analyzer: Cs2Analyzer, id: Int): Boolean = try {
        val script = scripts.load(id)
        if (script == null) {
            false
        } else {
            script.returnSignature = analyzer.script(id)?.returnSignature
            analyzer.simulate(script).failure == null
        }
    } catch (e: Exception) {
        false
    }
}
