package com.projectx.quest.runtime

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.quest.solver.QuestSolverRegistry
import com.projectx.script.api.varps

object StepAdvancer {
    private const val TICK_MS = 250L

    @Volatile private var lastTickMs = 0L

    fun maybeTick(autoAdvanceEnabled: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastTickMs < TICK_MS) return
        lastTickMs = now

        if (!loggedIn()) return
        val q = ActiveQuestState.selectedQuest ?: return

        // Track varbit value for the UI panel, but DO NOT auto-jump the step pointer when it
        // changes mid-quest. Intermediate values don't map 1:1 to lua step indices; jumping
        // is more annoying than helpful. The endpoints (start=0, >= complete=done) are still
        // honoured on quest-select via [ActiveQuestState.seekToVarbitStage].
        val vb = q.stageVarbit
        if (vb >= 0) {
            val current = runCatching { varps.getVarBit(vb) }.getOrElse { ActiveQuestState.lastVarbitValue }
            if (current != ActiveQuestState.lastVarbitValue) {
                ActiveQuestState.updateLastVarbit(current)
            }
        }

        if (!autoAdvanceEnabled) return

        val idx = ActiveQuestState.currentStepIndex
        val step = q.steps.getOrNull(idx) ?: return

        // Jump conditions: non-linear branching used by quests that need to loop
        // back when a model goes missing / dialog state regresses. All
        // jumpconditions must be met before we jump.
        if (step.jumpconditions.isNotEmpty() && step.jumpOffset != 0) {
            val implementable = step.jumpconditions.filter { ConditionEvaluator.implementable(it) }
            if (implementable.isNotEmpty() && implementable.all { ConditionEvaluator.isMet(it) }) {
                ActiveQuestState.jumpBy(step.jumpOffset)
                return
            }
        }

        val solverSolved = step.solverId?.let {
            QuestSolverRegistry.evaluateOrCached(q, step, idx, lastTickMs).solved
        } ?: false

        val implementable = step.postconditions.filter { ConditionEvaluator.implementable(it) }
        val conditionsMet = implementable.isNotEmpty() && implementable.all { ConditionEvaluator.isMet(it) }

        if (solverSolved || conditionsMet) {
            ActiveQuestState.next()
        }
    }

    private fun loggedIn(): Boolean = try {
        Bootstrap.client.mainState == MainState.LOGGED_IN
    } catch (_: Throwable) {
        false
    }
}
