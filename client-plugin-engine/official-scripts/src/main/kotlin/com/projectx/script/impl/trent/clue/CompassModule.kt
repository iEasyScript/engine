package com.projectx.script.impl.trent.clue

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.math.Vector2f
import com.projectx.game.math.WorldToScreen
import com.projectx.quest.overlay.pulseAlpha
import com.projectx.script.api.localPlayer
import com.projectx.script.api.varcs
import com.projectx.script.api.varps
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

private const val DEST_COORD_VARC = 1323
private const val COMPASS_SOURCE_VARBIT = 47024
private const val DIG_ICON_LAYER = 0


private val COMPASS_INTERFACES = intArrayOf(996, 627)

private val COMPASS_POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/**
 * Compass clues. The client cannot draw its needle without knowing where the needle points, so the exact
 * dig tile is sitting in the `trail_destcoord` client var as a packed coordgrid - no triangulation
 * needed.
 *
 * The readout stays up for as long as a destination is set, not just while the compass panel is open:
 * on the tile itself when it is in view, and on a screen-edge arrow pointing at it when it is not.
 */
class CompassModule : ClueModule {

    override val name = "Compass"

    private var panelInterface = -1
    private var target: Tile? = null
    private var canExcavate = false

    override fun active(): Boolean {
        panelInterface = COMPASS_INTERFACES.firstOrNull { isOnScreen(it) } ?: -1
        return panelInterface >= 0 && destination() != null
    }

    override fun reset() {
        panelInterface = -1
        target = null
        canExcavate = false
    }

    /**
     * Arrival is a plain tile match, exactly as the client tests it - `trail_compass_open_source` names
     * which UI opened the compass (scroll vs tetracompass), not whether the spot has been reached.
     */
    override fun update() {
        target = destination()
        val here = runCatching { localPlayer.tile }.getOrNull()
        canExcavate = here != null && here == target
    }

    override fun nextAction(): ClueAction? {
        if (!canExcavate || panelInterface < 0) return null
        val id = panelInterface
        return ClueAction("excavate compass spot") { IFSlot(id, DIG_ICON_LAYER).click() }
    }

    override fun render(scope: BackgroundDrawListScope) {
        val destination = target ?: return
        val here = runCatching { localPlayer.tile }.getOrNull() ?: return
        val color = if (canExcavate) ImGuiColors.GREEN or (255 shl 24) else pulseAlpha(ImGuiColors.CYAN, minAlpha = 170)
        val readout = readout(here, destination)
        with(scope) {
            val height = groundHeight(destination)
            val onScreen = destination.level == here.level && screenPoint(destination, height) != null
            if (onScreen) {
                tile(destination, color, height)
                textOnTile(destination, color, readout, heightFine = height)
            } else {
                edgeBearing(here, destination, color, readout)
            }
            if (panelInterface >= 0) {
                if (canExcavate) rectOf(panelInterface, DIG_ICON_LAYER)?.let { outline(it, color, thickness = 3f, pad = 3f) }
                rectOf(panelInterface, 0)?.let { panel(it, lines(destination, readout), color) }
            }
        }
    }

    private fun destination(): Tile? =
        packedTile(runCatching { varcs.getVar(DEST_COORD_VARC) }.getOrDefault(0))

    private fun screenPoint(destination: Tile, height: Float): Vector2f? {
        val point = runCatching { WorldToScreen.getEstimatedTileCenter(destination, height) }.getOrNull() ?: return null
        val (width, height) = ImGuiDsl.getDisplaySize()
        return point.takeIf { it.x in 0f..width && it.y in 0f..height }
    }

    private fun readout(here: Tile, destination: Tile): String {
        if (canExcavate) return "Dig here"
        val dx = destination.x - here.x
        val dy = destination.y - here.y
        return "${max(abs(dx), abs(dy))} tiles ${compassPoint(dx, dy)}"
    }

    private fun lines(destination: Tile, readout: String) = buildList {
        add(name)
        add("Dig at ${destination.x}, ${destination.y}${if (destination.level > 0) " lvl ${destination.level}" else ""}")
        add(readout)
    }

    private fun compassPoint(dx: Int, dy: Int): String {
        val degrees = Math.toDegrees(Math.atan2(dx.toDouble(), dy.toDouble()))
        return COMPASS_POINTS[Math.floorMod(Math.round(degrees / 45.0).toInt(), 8)]
    }
}
