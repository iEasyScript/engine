package com.projectx.quest.runtime

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.quest.data.Quest
import com.projectx.script.api.varps

object ActiveQuestState {
    @Volatile var selectedQuest: Quest? = null
        private set

    @Volatile var currentStepIndex: Int = 0
        private set

    @Volatile var lastVarbitValue: Int = -1
        private set

    fun select(quest: Quest?) {
        selectedQuest = quest
        currentStepIndex = 0
        lastVarbitValue = -1
        if (quest != null) seekToVarbitStage(quest)
    }

    fun next() {
        val q = selectedQuest ?: return
        if (currentStepIndex < q.steps.size) currentStepIndex++
    }

    fun previous() {
        if (currentStepIndex > 0) currentStepIndex--
    }

    fun reset() { currentStepIndex = 0 }

    fun setStep(index: Int) {
        val q = selectedQuest ?: return
        currentStepIndex = index.coerceIn(0, q.steps.size)
    }

    fun jumpBy(offset: Int) {
        val q = selectedQuest ?: return
        currentStepIndex = (currentStepIndex + offset).coerceIn(0, q.steps.size)
    }

    fun seekToVarbitStage() {
        val quest = selectedQuest ?: return
        seekToVarbitStage(quest)
    }

    fun updateLastVarbit(value: Int) { lastVarbitValue = value }

    // Varbit stage tracking — only the two endpoints are reliable. The varbit's intermediate
    // values do NOT map cleanly to the Lua step list (each varbit value covers many lua
    // steps), so interpolating jumps the user mid-quest. Use postconditions for step-by-step
    // progression and only let the varbit force the helper to the start or the finish.
    fun seekToVarbitStage(quest: Quest) {
        if (Bootstrap.client.mainState != MainState.LOGGED_IN) return
        val vb = quest.stageVarbit
        val complete = quest.stageVarbitCompleteValue
        if (vb < 0 || complete <= 0) return
        val value = runCatching { varps.getVarBit(vb) }.getOrNull() ?: return
        lastVarbitValue = value
        when {
            value <= 0 -> currentStepIndex = 0
            value >= complete -> currentStepIndex = quest.steps.size
            // Intermediate value — trust whatever step the user is on; do NOT jump.
        }
    }
}
