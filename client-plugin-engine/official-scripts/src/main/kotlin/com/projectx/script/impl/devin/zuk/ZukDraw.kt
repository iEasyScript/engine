package com.projectx.script.impl.devin.zuk

import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen.worldToScreen
import com.projectx.game.nxt.HeightMap
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import world.gregs.voidps.type.Tile

/**
 * Fills [tiles] as one merged surface: every cell is filled but an edge is stroked only where the
 * set has no neighbour, so a contiguous cluster reads as a single outlined region, not a grid.
 */
internal fun BackgroundDrawListScope.tileRegion(tiles: Collection<Tile>, color: Int) {
    if (tiles.isEmpty()) return
    val set = tiles as? Set<Tile> ?: tiles.toHashSet()
    val fill = (color and 0x00FFFFFF) or 0x19000000
    for (tile in set) {
        val q = tileCorners(tile) ?: continue
        convexPolyFilled(q, fill)
        if (Tile.of(tile.x - 1, tile.y, tile.plane) !in set) line(Vector2f(q[0], q[1]), Vector2f(q[2], q[3]), color, 2f)
        if (Tile.of(tile.x + 1, tile.y, tile.plane) !in set) line(Vector2f(q[6], q[7]), Vector2f(q[4], q[5]), color, 2f)
        if (Tile.of(tile.x, tile.y - 1, tile.plane) !in set) line(Vector2f(q[0], q[1]), Vector2f(q[6], q[7]), color, 2f)
        if (Tile.of(tile.x, tile.y + 1, tile.plane) !in set) line(Vector2f(q[2], q[3]), Vector2f(q[4], q[5]), color, 2f)
    }
}

private fun tileCorners(tile: Tile): FloatArray? {
    val x0 = tile.x * 512
    val x1 = (tile.x + 1) * 512
    val y0 = tile.y * 512
    val y1 = (tile.y + 1) * 512
    val sw = groundCorner(tile.plane, x0, y0) ?: return null
    val nw = groundCorner(tile.plane, x0, y1) ?: return null
    val ne = groundCorner(tile.plane, x1, y1) ?: return null
    val se = groundCorner(tile.plane, x1, y0) ?: return null
    return floatArrayOf(sw.x, sw.y, nw.x, nw.y, ne.x, ne.y, se.x, se.y)
}

private fun groundCorner(plane: Int, fineX: Int, fineY: Int): Vector2f? {
    val z = HeightMap.fineHeight(plane, fineX, fineY)?.toFloat() ?: return null
    return worldToScreen(Vector3f(fineX.toFloat(), fineY.toFloat(), z))
}
