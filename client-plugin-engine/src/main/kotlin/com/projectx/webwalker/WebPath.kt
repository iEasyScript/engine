package com.projectx.webwalker

import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/**
 * A walkable route, one tile per step from the start to the destination, on a single plane.
 *
 * [crossesDoor] marks each step that passes a door, so the walker can stop before it and open it. Tiles are
 * reachable from Java through [getX] and [getY]; Kotlin callers can use [tiles].
 */
class WebPath internal constructor(
    val plane: Int,
    private val xs: IntArray,
    private val ys: IntArray,
    private val doors: BooleanArray,
) {
    val size: Int get() = xs.size

    val lastIndex: Int get() = xs.size - 1

    val doorCount: Int get() = doors.count { it }

    val tiles: List<Tile> get() = xs.indices.map { Tile.of(xs[it], ys[it], plane) }

    fun getX(index: Int): Int = xs[index]

    fun getY(index: Int): Int = ys[index]

    fun tile(index: Int): Tile = Tile.of(xs[index], ys[index], plane)

    /** True when stepping onto [index] from the tile before it passes a door. */
    fun crossesDoor(index: Int): Boolean = doors[index]

    /** The first step at or after [fromIndex] that passes a door, or -1. */
    fun nextDoor(fromIndex: Int): Int {
        for (i in fromIndex.coerceAtLeast(1) until xs.size) if (doors[i]) return i
        return -1
    }

    /** The step nearest ([x], [y]) in tiles, counting diagonals as one. */
    fun nearestIndex(x: Int, y: Int): Int {
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in xs.indices) {
            val distance = distance(i, x, y)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    fun distance(index: Int, x: Int, y: Int): Int = max(abs(xs[index] - x), abs(ys[index] - y))

    override fun toString(): String = "WebPath(${size} tiles, $doorCount doors, plane $plane)"
}
