package com.projectx.markers

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.MainState
import com.projectx.quest.editor.TilePickResolver
import com.projectx.script.api.localPlayer
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGUIRender

/**
 * Background-drawlist overlay for grouped tile markers — pure drawing, never reads or
 * consumes input. Tiles of the same effective color render as a seamless mapSquare: each
 * cell is filled, but only edges on the mapSquare boundary are stroked, so a rectangle reads
 * as one outlined area instead of a grid. Also draws the tool's hovered/frozen crosshair.
 * Click handling lives in [TileMarkerInputSurface]. Auto-discovered via [ImGUIRender].
 */
object TileMarkerOverlay {
    @Volatile private var wasToolActive = false

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        try {
            val active = TileMarkerState.toolActive.value
            if (wasToolActive && !active) TileMarkerStore.flush()
            wasToolActive = active

            if (!isLoggedIn()) return
            val plane = runCatching { localPlayer.tile.plane }.getOrNull() ?: return

            val tiles = TileMarkerStore.visibleOnPlane(plane)
            val byColor = HashMap<Int, HashSet<Long>>()
            tiles.forEach { byColor.getOrPut(it.color) { HashSet() }.add(packed(it.x, it.y)) }

            backgroundDrawList {
                tiles.forEach { drawMapSquareCell(it, byColor.getValue(it.color)) }
                tiles.forEach { drawLabel(it) }
                if (TileMarkerState.toolActive.value) drawCursor()
            }
        } catch (t: Throwable) {
            println("[TileMarkerOverlay] render error: ${t.message}")
        }
    }

    private fun BackgroundDrawListScope.drawMapSquareCell(m: TileMarkerStore.Rendered, mapSquare: Set<Long>) {
        // Sample height at each corner vertex (on the 512-unit tile grid) rather than the tile
        // centre, so neighbouring cells project the shared edge to the same screen point — no
        // seams on sloped ground.
        val x0 = m.x * 512; val x1 = (m.x + 1) * 512
        val y0 = m.y * 512; val y1 = (m.y + 1) * 512
        val tl = corner(m.plane, x0, y0) ?: return
        val bl = corner(m.plane, x0, y1) ?: return
        val br = corner(m.plane, x1, y1) ?: return
        val tr = corner(m.plane, x1, y0) ?: return

        val fill = (m.color and 0x00FFFFFF) or 0x33000000
        convexPolyFilled(floatArrayOf(tl.x, tl.y, bl.x, bl.y, br.x, br.y, tr.x, tr.y), fill)

        if (packed(m.x - 1, m.y) !in mapSquare) line(tl, bl, m.color, 2f)
        if (packed(m.x + 1, m.y) !in mapSquare) line(tr, br, m.color, 2f)
        if (packed(m.x, m.y - 1) !in mapSquare) line(tl, tr, m.color, 2f)
        if (packed(m.x, m.y + 1) !in mapSquare) line(bl, br, m.color, 2f)
    }

    private fun BackgroundDrawListScope.drawLabel(m: TileMarkerStore.Rendered) {
        if (m.label.isBlank()) return
        val z = HeightMap.fineHeight(m.plane, m.x * 512 + 256, m.y * 512 + 256)?.toFloat() ?: return
        val center = WorldToScreen.worldToScreen(Vector3f(m.x * 512f + 256f, m.y * 512f + 256f, z)) ?: return
        text(center.transform(0f, -18f), m.color, m.label)
    }

    private fun BackgroundDrawListScope.drawCursor() {
        val (mx, my) = NativeBridge.getMousePos()
        val hit = TilePickResolver.resolve(mx, my) ?: return
        val z = HeightMap.fineHeight(hit.plane, hit.tileX * 512 + 256, hit.tileY * 512 + 256)?.toFloat() ?: return
        val fine = Vector3f(hit.tileX * 512f + 256f, hit.tileY * 512f + 256f, z)
        tile(fine, TileMarkerState.activeColor())
        WorldToScreen.worldToScreen(fine)?.let {
            text(it.transform(12f, -12f), TileMarkerState.activeColor(), "(${hit.tileX}, ${hit.tileY}, ${hit.plane})")
        }
    }

    private fun corner(plane: Int, fineX: Int, fineY: Int): Vector2f? {
        val z = HeightMap.fineHeight(plane, fineX, fineY)?.toFloat() ?: return null
        return WorldToScreen.worldToScreen(Vector3f(fineX.toFloat(), fineY.toFloat(), z))
    }

    private fun packed(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun isLoggedIn(): Boolean = try {
        Bootstrap.client.mainState == MainState.LOGGED_IN
    } catch (_: Throwable) { false }
}
