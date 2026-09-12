package com.projectx.quest.solver

import com.projectx.game.hooks.impl.InterfaceTextCapture
import com.projectx.puzzle.combolock.CombinationLock
import com.projectx.puzzle.combolock.CombinationLockLayout
import com.projectx.puzzle.combolock.FremennikRiddle
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.runtime.RecentChat
import com.projectx.ui.backend.dsl.utils.ImGuiColors

/**
 * Drives Peer the Seer's door riddle in The Fremennik Trials. It reads the riddle the moment the door
 * shows it — from the open dialog interfaces, falling back to the recent-chat buffer — derives and caches
 * the 4-letter answer ([FremennikRiddle]), then, while the seer combination lock ([CombinationLockLayout.SEER],
 * interface 298 `seer_combolock`) is open, highlights the correct arrow on every off-target dial (with a
 * live click count) and the confirm button once all four match. Before the lock is open it surfaces the
 * answer (once read) as a panel hint.
 */
object FremennikTrialsCombinationLockSolver : QuestStepSolver {
    override val id = "the-fremennik-trials.combination-lock"

    private val lock = CombinationLockLayout.SEER
    private var answer: String? = null

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        readRiddle()?.let { answer = it }
        val target = answer ?: return QuestStepSolver.Result()

        if (lock.isOpen()) {
            val current = lock.readDials()
            if (current != null && target.length == lock.dials) {
                return QuestStepSolver.Result(overlayActions = arrowActions(current, target))
            }
        }
        return QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint("Riddle answer: $target")))
    }

    private fun readRiddle(): String? {
        val dialogText = runCatching {
            InterfaceTextCapture.textFor(InterfaceTextCapture.DIALOG_INTERFACES)
        }.getOrDefault("")
        return FremennikRiddle.answerFrom(dialogText)
            ?: FremennikRiddle.answerMatching { RecentChat.containsRecent(it) }
    }

    private fun arrowActions(current: CharArray, target: String): List<QuestAction> {
        val actions = mutableListOf<QuestAction>()
        var allMatched = true
        for (i in 0 until lock.dials) {
            val move = CombinationLock.move(current[i], target[i])
            if (move.solved) continue
            allMatched = false
            actions += QuestAction.InterfaceComponentHighlight(
                interfaceId = lock.interfaceId,
                componentId = lock.arrowComponent(i, move.direction),
                label = move.clicks.toString(),
                color = ImGuiColors.CYAN,
            )
        }
        if (allMatched) {
            actions += QuestAction.InterfaceComponentHighlight(
                interfaceId = lock.interfaceId,
                componentId = lock.enterComponent,
                label = "Enter",
                color = ImGuiColors.CYAN,
            )
        }
        return actions
    }
}
