package com.projectx.pathfinder

import world.gregs.voidps.type.Tile

/**
 * Breadth-first searches over one plane of a square window, every step costing one, diagonals included: the way
 * the server walks, so a search distance compares directly with a count of route tiles.
 */
internal class SearchWindow(val originX: Int, val originY: Int, val dim: Int, val plane: Int) {
    fun contains(x: Int, y: Int): Boolean = x >= originX && y >= originY && x < originX + dim && y < originY + dim

    fun index(x: Int, y: Int): Int = (y - originY) * dim + (x - originX)

    fun x(index: Int): Int = originX + index % dim

    fun y(index: Int): Int = originY + index / dim

    companion object {
        fun around(x: Int, y: Int, plane: Int, radius: Int): SearchWindow =
            SearchWindow(x - radius, y - radius, radius * 2 + 1, plane)
    }
}

internal object GridSearch {
    const val UNREACHED = -1

    /** An unreachable click walks to the nearest reached tile within this many tiles of it. */
    const val ALTERNATIVE_RADIUS = 10

    /** Steps from the start to every tile within [maxDepth], [UNREACHED] elsewhere. */
    fun fill(rule: StepRule, window: SearchWindow, startX: Int, startY: Int, maxDepth: Int): IntArray {
        val dist = IntArray(window.dim * window.dim) { UNREACHED }
        if (!window.contains(startX, startY)) return dist
        val queue = IntArray(window.dim * window.dim)
        var head = 0
        var tail = 0
        val start = window.index(startX, startY)
        queue[tail++] = start
        dist[start] = 0
        while (head < tail) {
            val cell = queue[head++]
            val depth = dist[cell]
            if (depth >= maxDepth) continue
            val x = window.x(cell)
            val y = window.y(cell)
            for (direction in Steps.SERVER_ORDER) {
                val nx = x + direction.delta.x
                val ny = y + direction.delta.y
                if (!window.contains(nx, ny)) continue
                val next = window.index(nx, ny)
                if (dist[next] != UNREACHED || !rule.canStep(x, y, window.plane, direction)) continue
                dist[next] = depth + 1
                queue[tail++] = next
            }
        }
        return dist
    }

    /** Steps from [from] to [to], stopping as soon as it is reached; [UNREACHED] beyond [maxDepth]. */
    fun distance(rule: StepRule, window: SearchWindow, from: Tile, to: Tile, maxDepth: Int): Int {
        if (from.plane != to.plane || !window.contains(from.x, from.y) || !window.contains(to.x, to.y)) return UNREACHED
        if (from.x == to.x && from.y == to.y) return 0
        val dist = IntArray(window.dim * window.dim) { UNREACHED }
        val queue = IntArray(window.dim * window.dim)
        var head = 0
        var tail = 0
        val start = window.index(from.x, from.y)
        val target = window.index(to.x, to.y)
        queue[tail++] = start
        dist[start] = 0
        while (head < tail) {
            val cell = queue[head++]
            val depth = dist[cell]
            if (depth >= maxDepth) continue
            val x = window.x(cell)
            val y = window.y(cell)
            for (direction in Steps.SERVER_ORDER) {
                val nx = x + direction.delta.x
                val ny = y + direction.delta.y
                if (!window.contains(nx, ny)) continue
                val next = window.index(nx, ny)
                if (dist[next] != UNREACHED || !rule.canStep(x, y, window.plane, direction)) continue
                if (next == target) return depth + 1
                dist[next] = depth + 1
                queue[tail++] = next
            }
        }
        return UNREACHED
    }

    /**
     * The tiles the server walks for a walk click on [to] from [from], [from] first. Its search tries each tile's
     * neighbours W E S N SW SE NW NE and keeps the first parent found, so of the many equally short walks this is
     * the one it takes. An unreachable click walks to the reached tile within [ALTERNATIVE_RADIUS] that is nearest
     * in a straight line, then cheapest, scanning west to east and south to north. Null when nothing near it is.
     */
    fun serverPath(rule: StepRule, window: SearchWindow, from: Tile, to: Tile): List<Tile>? {
        if (from.plane != to.plane || !window.contains(from.x, from.y)) return null
        val size = window.dim * window.dim
        val cost = IntArray(size) { UNREACHED }
        val previous = IntArray(size)
        val queue = IntArray(size)
        var head = 0
        var tail = 0
        val start = window.index(from.x, from.y)
        val destination = if (window.contains(to.x, to.y)) window.index(to.x, to.y) else -1
        queue[tail++] = start
        cost[start] = 0
        previous[start] = -1
        while (head < tail) {
            val cell = queue[head++]
            if (cell == destination) break
            val x = window.x(cell)
            val y = window.y(cell)
            for (direction in Steps.SERVER_ORDER) {
                val nx = x + direction.delta.x
                val ny = y + direction.delta.y
                if (!window.contains(nx, ny)) continue
                val next = window.index(nx, ny)
                if (cost[next] != UNREACHED || !rule.canStep(x, y, window.plane, direction)) continue
                cost[next] = cost[cell] + 1
                previous[next] = cell
                queue[tail++] = next
            }
        }
        var end = destination
        if (end < 0 || cost[end] == UNREACHED) {
            end = -1
            var bestDistance = Int.MAX_VALUE
            var bestCost = Int.MAX_VALUE
            for (x in to.x - ALTERNATIVE_RADIUS..to.x + ALTERNATIVE_RADIUS) {
                for (y in to.y - ALTERNATIVE_RADIUS..to.y + ALTERNATIVE_RADIUS) {
                    if (!window.contains(x, y)) continue
                    val steps = cost[window.index(x, y)]
                    if (steps == UNREACHED) continue
                    val straight = (x - to.x) * (x - to.x) + (y - to.y) * (y - to.y)
                    if (straight < bestDistance || straight == bestDistance && steps < bestCost) {
                        bestDistance = straight
                        bestCost = steps
                        end = window.index(x, y)
                    }
                }
            }
            if (end < 0) return null
        }
        val path = arrayOfNulls<Tile>(cost[end] + 1)
        var cell = end
        for (i in path.indices.reversed()) {
            path[i] = Tile.of(window.x(cell), window.y(cell), window.plane)
            cell = previous[cell]
        }
        return path.map { it!! }
    }
}
