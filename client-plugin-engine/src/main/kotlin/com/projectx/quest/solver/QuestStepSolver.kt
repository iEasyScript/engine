package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep

/**
 * Programmatic driver for a single quest step whose correct highlight/advance
 * logic depends on dynamic game state — slide puzzles, dynamic orb positions,
 * combination locks, mazes, etc. Static JSON `actions` and `postconditions`
 * can't describe those because the right answer changes per attempt.
 *
 * A step references a solver via [QuestStep.solverId]; the solver runs each
 * tick while that step is active. It can:
 *  - Return [overlayActions] — extra [QuestAction]s rendered alongside the
 *    step's static actions (typically [QuestAction.ModelHighlight] /
 *    [QuestAction.ConversationHighlight] / [QuestAction.InventoryHighlight]
 *    pointing at the *currently-correct* target).
 *  - Return [solved] — when true, the helper auto-advances to the next step
 *    (combined with any static postconditions via AND).
 *
 * Solvers should be pure functions of game state — keep no side effects across
 * ticks beyond inert caching. They run on the client tick thread.
 */
interface QuestStepSolver {
    /** Stable id matched against [QuestStep.solverId]. */
    val id: String

    /**
     * Called every quest-helper tick (~250ms) while the owning step is active.
     * Defaults are empty / not-solved so partial implementations are safe.
     */
    fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): Result

    data class Result(
        val overlayActions: List<QuestAction> = emptyList(),
        val solved: Boolean = false,
    )
}

/**
 * Annotation for auto-discovery via classpath scanning. A solver class with
 * `@QuestSolver` is instantiated and registered at startup (similar to how
 * scripts are discovered).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class QuestSolver(val id: String)
