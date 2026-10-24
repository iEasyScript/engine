package com.projectx.traversal.walk

import com.projectx.pathfinder.WalkMap
import world.gregs.voidps.type.Tile

/**
 * Which route tile to click next, chosen by live walking distance: the furthest tile within the look-ahead whose walk
 * from the player costs no more than its distance along the route plus [SLACK]. The server's pathfinder then follows
 * the route instead of a detour, a tile near in a straight line but far by walking - across a wall, round a U-turn -
 * is never clicked, and a blocked tile is skipped.
 */
internal object ClickPicker {
    const val SLACK = 3

    /** Walk clicks further than 40 tiles from the player are refused, and the drawn tile trails the server's. */
    const val MAX_CLICK = 38

    /** [tile] is null when nothing ahead is reachable; [atEnd] when the player already stands at the route's end. */
    data class Pick(val tile: Tile?, val index: Int, val nearestIndex: Int, val onRoute: Boolean, val atEnd: Boolean)

    /**
     * The click for a player on [player], or null when the map is untrusted or no route tile shares its plane. Scans
     * from [fromIndex] so a route that passes near itself never pulls the player back. With [exactEnd] the last tile
     * must be stood on, not beside.
     */
    fun choose(map: WalkMap, route: List<Tile>, player: Tile, stepLimit: Int, fromIndex: Int, exactEnd: Boolean = false): Pick? {
        if (route.isEmpty()) return null
        val field = map.field(player, minOf(WalkMap.DEFAULT_MAX_DEPTH, stepLimit * 2 + 20)) ?: return null

        var nearest = -1
        var best = Int.MAX_VALUE
        for (i in fromIndex.coerceIn(0, route.size - 1) until route.size) {
            val tile = route[i]
            if (tile.plane != player.plane) continue
            val d = player.getLongestDelta(tile)
            if (d < best) {
                best = d
                nearest = i
            }
        }
        if (nearest < 0) return null

        val limit = minOf(stepLimit, MAX_CLICK)
        var onRoute = -1
        var anyReachable = -1
        for (i in nearest until route.size) {
            val tile = route[i]
            if (tile.plane != player.plane) break
            val delta = player.getLongestDelta(tile)
            val finalTile = exactEnd && i == route.size - 1
            if (if (finalTile) delta == 0 else delta <= 1) continue
            if (delta > limit) {
                if (i - nearest > 3 * limit) break
                continue
            }
            val d = field.distanceTo(tile)
            if (d < 0 || d > limit + SLACK) continue
            anyReachable = i
            if (d <= best + (i - nearest) + SLACK) onRoute = i
        }
        val atEnd = nearest == route.size - 1 && best <= (if (exactEnd) 0 else 1)
        return when {
            onRoute >= 0 -> Pick(route[onRoute], onRoute, nearest, true, atEnd)
            anyReachable >= 0 -> Pick(route[anyReachable], anyReachable, nearest, false, atEnd)
            else -> Pick(null, -1, nearest, false, atEnd)
        }
    }

    /**
     * Progress after [pick]: its nearest index, but only when the player is on the route there. Picked from afar - a
     * teleport onto the route still landing - the straight-line nearest tile can be any tile, often the end.
     */
    fun advance(progress: Int, pick: Pick, route: List<Tile>, player: Tile, stepLimit: Int): Int {
        val near = route[pick.nearestIndex]
        if (player.getLongestDelta(near) > minOf(stepLimit, MAX_CLICK)) return progress
        return maxOf(progress, pick.nearestIndex)
    }
}
