package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import java.util.concurrent.ConcurrentHashMap

/**
 * Lookup table for [QuestStepSolver]s by id. Solvers register here at startup
 * (manual registration or classpath discovery via [@QuestSolver]). The quest
 * runtime queries this on each tick of a solver-bound step.
 *
 * Result snapshots are cached for one tick so the renderer and step advancer
 * see consistent state instead of evaluating the solver twice per frame.
 */
object QuestSolverRegistry {
    private val solvers = ConcurrentHashMap<String, QuestStepSolver>()

    @Volatile private var lastTickStamp = -1L
    @Volatile private var lastResult: QuestStepSolver.Result = QuestStepSolver.Result()
    @Volatile private var lastKey: String? = null

    fun register(solver: QuestStepSolver) {
        solvers[solver.id] = solver
    }

    fun unregister(id: String) {
        solvers.remove(id)
    }

    fun get(id: String): QuestStepSolver? = solvers[id]

    fun all(): List<QuestStepSolver> = solvers.values.toList()

    fun ids(): List<String> = solvers.keys.sorted()

    /**
     * Returns the solver result for the active step, evaluated at most once per
     * tick stamp [tickStamp]. Pass a monotonically-increasing stamp (e.g. the
     * client tick counter or System.currentTimeMillis()/250) so the renderer
     * and advancer share a single evaluation.
     */
    fun evaluateOrCached(
        quest: Quest,
        step: QuestStep,
        stepIndex: Int,
        tickStamp: Long,
    ): QuestStepSolver.Result {
        val solver = step.solverId?.let { solvers[it] } ?: return QuestStepSolver.Result()
        val key = "${quest.slug}#$stepIndex#${solver.id}"
        if (tickStamp == lastTickStamp && key == lastKey) return lastResult
        val r = try {
            solver.evaluate(quest, step, stepIndex)
        } catch (t: Throwable) {
            println("[QuestSolverRegistry] solver '${solver.id}' threw: ${t.message}")
            t.printStackTrace()
            QuestStepSolver.Result()
        }
        lastTickStamp = tickStamp
        lastKey = key
        lastResult = r
        return r
    }

    /** Convenience: only the overlay actions, no advance-side effects. */
    fun overlayActionsFor(
        quest: Quest,
        step: QuestStep,
        stepIndex: Int,
        tickStamp: Long,
    ): List<QuestAction> = evaluateOrCached(quest, step, stepIndex, tickStamp).overlayActions
}
