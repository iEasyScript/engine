package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.data.WorldLocation
import com.projectx.quest.runtime.QuestInstanceTracker
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/**
 * Lunar Diplomacy "Dream puff" chasm crossing (Ethereal Guide). The player jumps across a chasm on
 * `quest_lunar_dream_puff_platform_multi` puffs; the correct route is fixed but hidden (every puff
 * shares one loc id, so it can't be read from the cache), and a wrong jump drops the player back to
 * the start. It has to be learned by trial and remembered.
 *
 * The solver learns the route from the player's own movement and highlights it so a fall is only ever
 * paid for once. A puff is confirmed safe when the player advances off it toward the far side (it held
 * them) or settles on it; it is marked unsafe and un-highlighted if the player falls back to the start
 * from it. Progress is measured as distance from the captured start tile, so the logic is geometry-
 * agnostic. Highlight-only; advancement is left to the step's existing dialogue postcondition.
 */
object LunarDiplomacyDreamPuffSolver : QuestStepSolver {
    override val id: String = "lunar-diplomacy.dream-puff"

    private const val PUFF_LOC = 16638
    private const val SCAN_RANGE = 12
    private const val STABLE_TICKS = 3

    private val safe = LinkedHashSet<Tile>()
    private val unsafe = HashSet<Tile>()
    private var startTile: Tile? = null
    private var curPuff: Tile? = null
    private var curTicks = 0
    private var lastOrigin: QuestInstanceTracker.Origin? = null

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        resetOnNewInstance()

        val player = runCatching { localPlayer.tile }.getOrNull()
            ?: return hint("Move to the chasm to start tracking the dream-puff route.")
        val standing = puffUnder(player)

        if (standing != null) onPuff(standing) else offPuffs(player)

        val highlights = safe.map(::highlight)
        val note = when {
            safe.isEmpty() -> "Jump the puffs to learn the safe route - it will highlight as you go."
            else -> "Safe puffs learned: ${safe.size} (highlighted). Retrace them after a fall."
        }
        return QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint(note)) + highlights)
    }

    private fun onPuff(standing: Tile) {
        if (standing == curPuff) {
            curTicks++
        } else {
            curPuff?.let { if (progress(standing) > progress(it)) confirmSafe(it) }
            curPuff = standing
            curTicks = 1
        }
        if (curTicks >= STABLE_TICKS) confirmSafe(standing)
    }

    private fun offPuffs(player: Tile) {
        if (startTile == null) startTile = player
        val left = curPuff
        if (left != null) {
            if (progress(player) > progress(left)) confirmSafe(left) else confirmUnsafe(left)
            curPuff = null
            curTicks = 0
        } else if (progress(player) == 0) {
            startTile = player
        }
    }

    private fun confirmSafe(tile: Tile) {
        unsafe.remove(tile)
        safe.add(tile)
    }

    private fun confirmUnsafe(tile: Tile) {
        safe.remove(tile)
        unsafe.add(tile)
    }

    /** Distance from the captured start tile; higher = further across the chasm. */
    private fun progress(tile: Tile): Int {
        val s = startTile ?: return 0
        if (tile.plane != s.plane) return 0
        return max(abs(tile.x - s.x), abs(tile.y - s.y))
    }

    private fun puffUnder(player: Tile): Tile? =
        runCatching { getAllObjectsWithinRange(SCAN_RANGE) }.getOrNull().orEmpty()
            .firstOrNull { it.id == PUFF_LOC && it.tile.plane == player.plane && player in it.occupiedTiles() }
            ?.tile

    private fun resetOnNewInstance() {
        val origin = runCatching { QuestInstanceTracker.origin }.getOrNull()
        if (origin != lastOrigin) {
            safe.clear(); unsafe.clear()
            startTile = null; curPuff = null; curTicks = 0
            lastOrigin = origin
        }
    }

    private fun highlight(tile: Tile) = QuestAction.ModelHighlight(
        kind = "object",
        typeId = PUFF_LOC,
        modelIds = emptyList(),
        displayName = "Safe puff",
        atLocation = WorldLocation(tile.x.toDouble(), 0.0, tile.y.toDouble()),
    )

    private fun hint(text: String) = QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint(text)))
}
