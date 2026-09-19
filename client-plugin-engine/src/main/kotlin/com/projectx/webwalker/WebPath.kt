package com.projectx.webwalker

import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/**
 * A walkable route, one tile per step from the start to the destination.
 *
 * [crossesDoor] marks each step that passes a door found in the cache, so the walker can stop before it and open
 * it. [linkAt] marks each step reached by a [WebLink] - a staircase, ladder, shortcut or curated door - which the
 * walker performs instead of walking onto. A route may change plane only at a link, so [getPlane] is per step.
 *
 * Tiles are reachable from Java through [getX], [getY] and [getPlane]; Kotlin callers can use [tiles].
 */
class WebPath internal constructor(
    private val xs: IntArray,
    private val ys: IntArray,
    private val planes: IntArray,
    private val doors: BooleanArray,
    private val links: Array<WebLink?>,
) {
    val size: Int get() = xs.size

    val lastIndex: Int get() = xs.size - 1

    val doorCount: Int get() = doors.count { it }

    val linkCount: Int get() = links.count { it != null }

    /** The plane the route starts on. A route that takes a link may end on another; see [getPlane]. */
    val plane: Int get() = if (planes.isEmpty()) 0 else planes[0]

    /** True when the route changes plane, which it can only do by taking a link. */
    val changesPlane: Boolean get() = planes.any { it != plane }

    val tiles: List<Tile> get() = xs.indices.map { Tile.of(xs[it], ys[it], planes[it]) }

    fun getX(index: Int): Int = xs[index]

    fun getY(index: Int): Int = ys[index]

    fun getPlane(index: Int): Int = planes[index]

    fun tile(index: Int): Tile = Tile.of(xs[index], ys[index], planes[index])

    /** True when stepping onto [index] from the tile before it passes a door the cache knows about. */
    fun crossesDoor(index: Int): Boolean = doors[index]

    /** The link taken to reach [index], or null when the step was walked. */
    fun linkAt(index: Int): WebLink? = links[index]

    /** The first step at or after [fromIndex] that passes a cache door, or -1. */
    fun nextDoor(fromIndex: Int): Int {
        for (i in fromIndex.coerceAtLeast(1) until xs.size) if (doors[i]) return i
        return -1
    }

    /** The first step at or after [fromIndex] reached by a link, or -1. */
    fun nextLink(fromIndex: Int): Int {
        for (i in fromIndex.coerceAtLeast(1) until xs.size) if (links[i] != null) return i
        return -1
    }

    /**
     * The step nearest ([x], [y]) on [plane] in tiles, counting diagonals as one.
     *
     * Restricted to the player's own plane: the same x and y on another floor is a different place, and matching
     * it would make the walker think it had already crossed a staircase it has not reached.
     */
    fun nearestIndex(x: Int, y: Int, plane: Int): Int {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (i in xs.indices) {
            if (planes[i] != plane) continue
            val distance = distance(i, x, y)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return if (best == -1) 0 else best
    }

    /** The step nearest ([x], [y]) on the plane the route starts on. */
    fun nearestIndex(x: Int, y: Int): Int = nearestIndex(x, y, plane)

    fun distance(index: Int, x: Int, y: Int): Int = max(abs(xs[index] - x), abs(ys[index] - y))

    override fun toString(): String =
        "WebPath($size tiles, $doorCount doors, $linkCount links, plane $plane${if (changesPlane) "+" else ""})"
}
