package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep

/**
 * Example solver that always advances the step — useful as a smoke test and as
 * the simplest possible reference implementation. Real solvers should inspect
 * game state (inventory, varbits, interface state, scene entities) and emit
 * dynamic [QuestAction] highlights pointing at the currently-correct target.
 *
 * To register more solvers at runtime (e.g. from a script module), call
 * [QuestSolverRegistry.register] from your initialisation block:
 *
 * ```
 * QuestSolverRegistry.register(MyPuzzleSolver())
 * ```
 *
 * Or annotate the class with [QuestSolver] and rely on classpath discovery
 * (a discovery hook can be added alongside [com.projectx.script.ScriptLoader]
 * scanning if/when needed).
 */
object NoopAutoAdvanceSolver : QuestStepSolver {
    override val id: String = "example.noop-advance"
    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result =
        QuestStepSolver.Result(solved = true)
}

/**
 * Skeleton solver demonstrating the shape of a puzzle solver. Reads no game
 * state and never advances — replace [evaluate]'s body with the actual logic
 * (e.g. read interface component child state, pick the right child id, emit a
 * [QuestAction.ConversationHighlight] pointing at it).
 */
object SkeletonPuzzleSolver : QuestStepSolver {
    override val id: String = "example.skeleton-puzzle"
    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        return QuestStepSolver.Result()
    }
}

/** Eagerly registers the example solvers so the editor's solver dropdown isn't empty. */
internal fun registerExampleSolvers() {
    QuestSolverRegistry.register(NoopAutoAdvanceSolver)
    QuestSolverRegistry.register(SkeletonPuzzleSolver)
    QuestSolverRegistry.register(EyesOfGlouphriePuzzleSolver)
    QuestSolverRegistry.register(ElementalWorkshopIIJunctionBoxSolver)
    QuestSolverRegistry.register(ElementalWorkshopIIILatticeSolver)
    QuestSolverRegistry.register(ElementalWorkshopIIITowerSolver)
    QuestSolverRegistry.register(DesperateTimesCombinationLockSolver)
    QuestSolverRegistry.register(DesperateTimesSlidePuzzleSolver)
    QuestSolverRegistry.register(DeadAndBuriedMapPuzzleSolver)
    QuestSolverRegistry.register(FremennikTrialsCombinationLockSolver)
    QuestSolverRegistry.register(SpiritOfSummerEmoteSolver)
    QuestSolverRegistry.register(LunarDiplomacyDiceSolver)
    QuestSolverRegistry.register(LunarDiplomacyNumberPatternSolver)
    QuestSolverRegistry.register(LunarDiplomacyDreamPuffSolver)
    QuestSolverRegistry.register(TheElderKilnScalesSolver)
    QuestSolverRegistry.register(TheBrinkOfExtinctionPedestalsSolver)
}
