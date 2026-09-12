package com.projectx.script.impl.trent.clue

import com.projectx.game.math.Vector2f
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.quest.overlay.arrow
import com.projectx.script.api.interfaces
import com.projectx.script.api.localPlayer
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import world.gregs.voidps.type.Tile
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

const val CHAR_WIDTH = 7f
const val LINE_HEIGHT = 16f

private const val PANEL_GAP = 8f
private const val PANEL_PADDING = 8f
private const val EDGE_MARGIN = 90f
private const val ARROW_LENGTH = 34f

fun isOpen(interfaceId: Int) =
    try { interfaces.isOpen(interfaceId) } catch (_: Throwable) { false }

/**
 * True when [interfaceId] is not merely resolvable but actually laid out on screen. Clue side panels
 * linger in the InterfaceList long after their step is over - a finished compass panel still reports
 * open, with its components still flagged visible, and only the absence of screen rects tells it apart
 * from a live one.
 */
fun isOnScreen(interfaceId: Int, probeComponents: IntRange = 0..8) =
    isOpen(interfaceId) && probeComponents.any { rectOf(interfaceId, it) != null }

/**
 * Ground height for a world marker, in world-fine units.
 *
 * The shared draw helpers fall back to world Z 0 whenever the height map has no sample for a tile, which
 * drops the marker to the floor of the world rather than onto the terrain - and they project decorations
 * at Z 0 by default too. Upper levels and instanced areas are exactly where samples go missing, so fall
 * back to the local player's own fine Z instead: a marker a few paces away on the same level sits far
 * closer to that than to zero.
 */
fun groundHeight(tile: Tile): Float {
    runCatching { HeightMap.fineHeight(tile) }.getOrNull()?.let { return it.toFloat() }
    return runCatching { localPlayer.graphNode.tileFine.z }.getOrDefault(0f)
}

/** Decodes a packed coordgrid - `(plane shl 28) or (x shl 14) or y` - or null for the unset value 0. */
fun packedTile(packed: Int): Tile? =
    if (packed == 0) null else Tile(packed shr 14 and 0x3FFF, packed and 0x3FFF, packed ushr 28 and 0x3)

fun rectOf(interfaceId: Int, componentId: Int): ScreenRect? =
    try {
        interfaces.getComponent(interfaceId, componentId)?.screenRect?.takeIf { it.width > 0 && it.height > 0 }
    } catch (_: Throwable) {
        null
    }

fun graphicOf(interfaceId: Int, componentId: Int) =
    try { interfaces.getComponent(interfaceId, componentId)?.graphicId ?: 0 } catch (_: Throwable) { 0 }

fun textOf(interfaceId: Int, componentId: Int) =
    try { interfaces.getComponent(interfaceId, componentId)?.text.orEmpty() } catch (_: Throwable) { "" }

/** The rect of one cell of a uniform grid laid out inside [grid], in row-major order. */
fun cellOf(grid: ScreenRect, rows: Int, cols: Int, slot: Int): ScreenRect {
    val width = grid.width / cols
    val height = grid.height / rows
    return ScreenRect(grid.x + (slot % cols) * width, grid.y + (slot / cols) * height, width, height)
}

fun BackgroundDrawListScope.outline(rect: ScreenRect, color: Int, thickness: Float = 2f, pad: Float = 0f) {
    rect(
        Vector2f(rect.x - pad, rect.y - pad),
        Vector2f(rect.x + rect.width + pad, rect.y + rect.height + pad),
        color,
        rounding = 4f,
        thickness = thickness,
    )
}

fun BackgroundDrawListScope.label(topCentre: Vector2f, content: String, color: Int) {
    val width = content.length * CHAR_WIDTH + 10f
    val x = topCentre.x - width / 2f
    rectFilled(Vector2f(x, topCentre.y), Vector2f(x + width, topCentre.y + LINE_HEIGHT + 2f), ImGuiColors.OVERLAY_DARK, rounding = 3f)
    text(Vector2f(x + 5f, topCentre.y + 1f), color, content)
}

/**
 * A number centred on a small cell, over a dark disc so it stays readable against whatever art the
 * interface draws underneath - interface squares are busy, and bare text on them is unreadable.
 */
fun BackgroundDrawListScope.countBadge(cell: ScreenRect, content: String, color: Int) {
    val centre = Vector2f(cell.x + cell.width / 2f, cell.y + cell.height / 2f)
    val radius = minOf(cell.width, cell.height) * 0.30f
    circleFilled(centre, radius, ImGuiColors.withAlpha(ImGuiColors.BLACK, 215))
    circle(centre, radius, color, segments = 0, thickness = 2f)
    text(
        Vector2f(centre.x - content.length * CHAR_WIDTH / 2f, centre.y - LINE_HEIGHT / 2f),
        color,
        content,
    )
}

fun BackgroundDrawListScope.centredText(cell: ScreenRect, content: String, color: Int) {
    text(
        Vector2f(cell.x + cell.width / 2f - content.length * CHAR_WIDTH / 2f, cell.y + cell.height / 2f - LINE_HEIGHT / 2f),
        color,
        content,
    )
}

/** An arrow pinned to the display edge on the bearing from [from] to [to], captioned with [caption]. */
fun BackgroundDrawListScope.edgeBearing(from: Tile, to: Tile, color: Int, caption: String) {
    val (width, height) = ImGuiDsl.getDisplaySize()
    val centreX = width / 2f
    val centreY = height / 2f
    // Screen Y grows downward, so the world bearing's Y component is flipped.
    val bearing = atan2((to.x - from.x).toDouble(), (to.y - from.y).toDouble())
    val stepX = sin(bearing).toFloat()
    val stepY = -cos(bearing).toFloat()
    val reach = buildList {
        if (stepX > 1e-3f) add((width - EDGE_MARGIN - centreX) / stepX)
        if (stepX < -1e-3f) add((EDGE_MARGIN - centreX) / stepX)
        if (stepY > 1e-3f) add((height - EDGE_MARGIN - centreY) / stepY)
        if (stepY < -1e-3f) add((EDGE_MARGIN - centreY) / stepY)
    }.filter { it > 0f }.minOrNull() ?: return
    val tipX = (centreX + stepX * reach).coerceIn(EDGE_MARGIN, width - EDGE_MARGIN)
    val tipY = (centreY + stepY * reach).coerceIn(EDGE_MARGIN, height - EDGE_MARGIN)
    arrow(
        Vector2f(tipX - stepX * ARROW_LENGTH, tipY - stepY * ARROW_LENGTH),
        Vector2f(tipX, tipY),
        color,
        thickness = 4f,
        headSize = 18f,
    )
    label(Vector2f(tipX - stepX * (ARROW_LENGTH + 14f), tipY - stepY * (ARROW_LENGTH + 14f) - LINE_HEIGHT / 2f), caption, color)
}

/**
 * A read-out panel pinned beside [anchor] - to its right when the display has room, otherwise to its
 * left, otherwise overlapping its left edge. Panels are stacked by [slot] so two active modules do not
 * draw over each other.
 */
fun BackgroundDrawListScope.panel(anchor: ScreenRect, lines: List<String>, accent: Int, slot: Int = 0) {
    if (lines.isEmpty()) return
    val width = lines.maxOf { it.length } * CHAR_WIDTH + PANEL_PADDING * 2
    val height = lines.size * LINE_HEIGHT + PANEL_PADDING * 2
    val (displayWidth, displayHeight) = ImGuiDsl.getDisplaySize()
    val right = anchor.x + anchor.width + PANEL_GAP
    val x = when {
        right + width <= displayWidth -> right
        anchor.x - PANEL_GAP - width >= 0f -> anchor.x - PANEL_GAP - width
        else -> anchor.x.toFloat()
    }
    val y = (anchor.y + slot * (height + PANEL_GAP)).coerceIn(0f, maxOf(0f, displayHeight - height))

    rectFilled(Vector2f(x, y), Vector2f(x + width, y + height), ImGuiColors.OVERLAY_DARK, rounding = 4f)
    rect(Vector2f(x, y), Vector2f(x + width, y + height), accent, rounding = 4f, thickness = 1.5f)
    lines.forEachIndexed { index, line ->
        val color = if (index == 0) ImGuiColors.WHITE else accent
        text(Vector2f(x + PANEL_PADDING, y + PANEL_PADDING + index * LINE_HEIGHT), color or (255 shl 24), line)
    }
}
