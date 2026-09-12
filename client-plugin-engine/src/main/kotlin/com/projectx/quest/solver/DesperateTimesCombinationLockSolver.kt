package com.projectx.quest.solver

import com.projectx.puzzle.combolock.ClueItem
import com.projectx.puzzle.combolock.CombinationClueReader
import com.projectx.puzzle.combolock.CombinationLock
import com.projectx.puzzle.combolock.CombinationLockInterface
import com.projectx.puzzle.combolock.CombinationPlaqueReader
import com.projectx.puzzle.combolock.CombinationRecipeReader
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.script.api.groundItems
import com.projectx.script.api.localPlayer
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import kotlin.math.abs

/**
 * Drives the Desperate Times chest combination locks (interface [CombinationLockInterface.INTERFACE_ID]).
 *
 * Each tick it works out the 4-letter target for the current room and, while the lock is open, highlights
 * the correct arrow on every off-target dial with a live click count (then Enter when all match). The
 * target comes from, in priority order:
 *  - a clue interface read this room and cached — the "Old recipe" (220, Roman-numeral / first-letter
 *    ingredients) or the lodestone "Plaque" (140, first letter of each depicted lodestone). The code is
 *    cached against the room so it survives the clue closing while the player works the lock.
 *  - the room's ground items ([CombinationClueReader]) — single-item / herb / coins / first-letters.
 *
 * When no code is known the solver highlights the clue source — the "Old recipe" NPC and/or the "Plaque"
 * object (whichever is present; the matcher no-ops on the absent one). Advisory only; state is touched
 * only from the main-logic tick.
 */
object DesperateTimesCombinationLockSolver : QuestStepSolver {
    override val id = "desperate-times.combination-lock"

    private const val ROOM_RADIUS = 8
    private const val OLD_RECIPE_NPC = 26311
    private const val PLAQUE_OBJECT = 37670

    private val readRecipeHighlight = QuestAction.ModelHighlight(
        kind = "npc",
        typeId = OLD_RECIPE_NPC,
        modelIds = emptyList(),
        candidateNpcTypeIds = listOf(OLD_RECIPE_NPC),
        displayName = "Read recipe",
    )
    private val readPlaqueHighlight = QuestAction.ModelHighlight(
        kind = "object",
        typeId = PLAQUE_OBJECT,
        modelIds = emptyList(),
        candidateObjectTypeIds = listOf(PLAQUE_OBJECT),
        displayName = "Read plaque",
    )

    // The code last read from a clue interface, cached against the room so it isn't reused at another chest.
    private var clueCode: String? = null
    private var clueAnchorX = 0
    private var clueAnchorY = 0
    private var clueAnchorPlane = Int.MIN_VALUE

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        // All chests done → the completed WORD GRID solution is on screen → advance to the slide puzzle.
        if (DesperateTimesSlidePuzzleSolver.isSolutionReady()) return QuestStepSolver.Result(solved = true)

        cacheClueIfInterfaceOpen()

        val target = cachedClueForThisRoom() ?: groundItemTarget()

        if (CombinationLockInterface.isOpen()) {
            val current = CombinationLockInterface.readDials()
            if (current != null && target != null && target.length == CombinationLockInterface.DIALS) {
                return QuestStepSolver.Result(overlayActions = arrowActions(current, target))
            }
        }

        // Code still unknown and we're not currently reading a clue interface → point at the clue source.
        if (target == null && !CombinationRecipeReader.isOpen() && !CombinationPlaqueReader.isOpen()) {
            return QuestStepSolver.Result(overlayActions = listOf(readRecipeHighlight, readPlaqueHighlight))
        }
        return QuestStepSolver.Result()
    }

    private fun arrowActions(current: CharArray, target: String): List<QuestAction> {
        val actions = mutableListOf<QuestAction>()
        var allMatched = true
        for (i in 0 until CombinationLockInterface.DIALS) {
            val move = CombinationLock.move(current[i], target[i])
            if (move.solved) continue
            allMatched = false
            actions += QuestAction.InterfaceComponentHighlight(
                interfaceId = CombinationLockInterface.INTERFACE_ID,
                componentId = CombinationLockInterface.arrowComponent(i, move.direction),
                label = move.clicks.toString(),
                color = ImGuiColors.CYAN,
            )
        }
        if (allMatched) {
            actions += QuestAction.InterfaceComponentHighlight(
                interfaceId = CombinationLockInterface.INTERFACE_ID,
                componentId = CombinationLockInterface.ENTER_COMPONENT,
                label = "Enter",
                color = ImGuiColors.CYAN,
            )
        }
        return actions
    }

    private fun cacheClueIfInterfaceOpen() {
        val code = CombinationRecipeReader.read() ?: CombinationPlaqueReader.read() ?: return
        val tile = runCatching { localPlayer.tile }.getOrNull() ?: return
        clueCode = code
        clueAnchorX = tile.x
        clueAnchorY = tile.y
        clueAnchorPlane = tile.plane
    }

    private fun cachedClueForThisRoom(): String? {
        val code = clueCode ?: return null
        val tile = runCatching { localPlayer.tile }.getOrNull() ?: return null
        if (tile.plane != clueAnchorPlane) return null
        if (abs(tile.x - clueAnchorX) > ROOM_RADIUS || abs(tile.y - clueAnchorY) > ROOM_RADIUS) return null
        return code
    }

    private fun groundItemTarget(): String? {
        val anchor = runCatching { localPlayer.tile }.getOrNull() ?: return null
        val ax = anchor.x
        val ay = anchor.y
        val plane = anchor.plane
        val items = runCatching {
            groundItems
                .filter {
                    it.tile.plane == plane &&
                        (it.tile.x - ax) in -ROOM_RADIUS..ROOM_RADIUS &&
                        (it.tile.y - ay) in -ROOM_RADIUS..ROOM_RADIUS
                }
                .map { ClueItem(it.name, it.amount, it.tile.x, it.tile.y) }
        }.getOrElse { return null }
        return CombinationClueReader.derive(items, ax, ay)
    }
}
