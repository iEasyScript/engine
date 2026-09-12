package com.projectx.quest.solver

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.data.WorldLocation
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.api.getAllObjectsWithinRange
import world.gregs.voidps.type.Tile
import kotlin.math.max
import kotlin.math.min

/**
 * Lunar Diplomacy "Dicing" puzzle (Ethereal Fluke). The NPC calls out a number and the six dice
 * must be rolled so their faces sum to it. Each die is a `quest_lunar_dice_*` multi-loc showing a
 * value 1-6; rolling one flips it to the opposite face of a d6 (1<->6, 2<->5, 3<->4).
 *
 * Each tick the solver reads the called number from chat, reads each die's current face from its
 * visible loc id, and brute-forces the target-sum assignment that needs the fewest rolls. It
 * highlights the dice still on the wrong face and advances the step once the faces already sum to
 * the target. The chosen final assignment is invariant of the current faces, so the highlighted set
 * only shrinks as the player rolls - it never jumps around. Highlight-only; never clicks.
 */
object LunarDiplomacyDiceSolver : QuestStepSolver {
    override val id: String = "lunar-diplomacy.dice"

    private const val SCAN_RANGE = 15

    /** Visible loc id -> face value. Covers both the plain and `_multi` gameval ranges. */
    private val FACE_BY_ID: Map<Int, Int> = buildMap {
        for (face in 1..6) {
            put(16842 + face, face)  // quest_lunar_dice_{1..6}up
            put(17018 + face, face)  // quest_lunar_dice_{1..6}up_multi
        }
    }

    private val CALLED_NUMBER = Regex("(?i)\\s+is[:\\s]*([0-9]+)")

    private var target: Int = -1

    private data class Die(val typeId: Int, val tile: Tile, val face: Int)

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        RecentChat.latestMatch(CALLED_NUMBER)?.groupValues?.get(1)?.toIntOrNull()?.let { target = it }

        val dice = readDice()
        if (dice.isEmpty()) {
            target = -1
            return hint("Stand by the dice and talk to the Ethereal Fluke to start.")
        }
        if (target < 0) return hint("Waiting for the Ethereal Fluke to call a number.")

        val flipMask = solve(dice, target)
            ?: return hint("Target $target - no valid combination for these dice.")

        if (flipMask == 0) return solved("Dice sum to $target - puzzle complete.")

        val toFlip = dice.filterIndexed { i, _ -> (flipMask shr i) and 1 == 1 }
        val hint = QuestAction.TextHint(
            "Target $target - roll ${toFlip.size} highlighted " +
                if (toFlip.size == 1) "die." else "dice.",
        )
        return QuestStepSolver.Result(overlayActions = listOf(hint) + toFlip.map(::highlight))
    }

    private fun readDice(): List<Die> =
        runCatching { getAllObjectsWithinRange(SCAN_RANGE) }.getOrNull().orEmpty()
            .mapNotNull { obj -> FACE_BY_ID[obj.id]?.let { Die(rawTypeId(obj), obj.tile, it) } }
            .distinctBy { it.tile }
            .sortedWith(compareBy({ it.tile.plane }, { it.tile.x }, { it.tile.y }))

    private fun rawTypeId(obj: SceneObject): Int = obj.typeId.let { if (it == -1) obj.id else it }

    /**
     * Returns the roll bitmask (bit i = flip die i) of the fewest-rolls assignment whose faces sum
     * to [target], or null if none exists. Enumerates final low/high face choices (invariant of the
     * current faces) so the tie-break is stable across the player's rolls.
     */
    private fun solve(dice: List<Die>, target: Int): Int? {
        val low = IntArray(dice.size) { min(dice[it].face, 7 - dice[it].face) }
        val high = IntArray(dice.size) { max(dice[it].face, 7 - dice[it].face) }
        var bestFlips = Int.MAX_VALUE
        var bestChoice = -1
        var bestFlipMask = -1
        for (choice in 0 until (1 shl dice.size)) {
            var sum = 0
            var flipMask = 0
            for (i in dice.indices) {
                val show = if ((choice shr i) and 1 == 1) high[i] else low[i]
                sum += show
                if (show != dice[i].face) flipMask = flipMask or (1 shl i)
            }
            if (sum != target) continue
            val flips = Integer.bitCount(flipMask)
            if (flips < bestFlips || (flips == bestFlips && choice < bestChoice)) {
                bestFlips = flips
                bestChoice = choice
                bestFlipMask = flipMask
            }
        }
        return if (bestChoice == -1) null else bestFlipMask
    }

    private fun highlight(die: Die) = QuestAction.ModelHighlight(
        kind = "object",
        typeId = die.typeId,
        modelIds = emptyList(),
        displayName = "Roll -> ${7 - die.face}",
        atLocation = WorldLocation(die.tile.x.toDouble(), 0.0, die.tile.y.toDouble()),
    )

    private fun hint(text: String) = QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint(text)))
    private fun solved(text: String) = QuestStepSolver.Result(solved = true, overlayActions = listOf(QuestAction.TextHint(text)))
}
