package com.projectx.pathfinder

import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile

/**
 * A synthetic plane for walking tests: open ground with blocked tiles and walls on tile sides. A diagonal step needs
 * both of its cardinal halves open, as in game.
 */
class GridWorld {
    private val blocked = HashSet<Long>()
    private val walls = HashSet<Long>()

    fun block(x: Int, y: Int) = apply { blocked += key(x, y) }

    fun blockRect(minX: Int, minY: Int, maxX: Int, maxY: Int) = apply {
        for (x in minX..maxX) for (y in minY..maxY) block(x, y)
    }

    /** A wall between (x, y) and its neighbour in [direction], which must be cardinal. */
    fun wall(x: Int, y: Int, direction: Direction) = apply {
        walls += edge(x, y, direction)
        walls += edge(x + direction.delta.x, y + direction.delta.y, direction.inverse())
    }

    internal val rule: StepRule = StepRule { x, y, _, d -> canStep(x, y, d) }

    internal fun map(): WalkMap = WalkMap(rule)

    private fun canStep(x: Int, y: Int, d: Direction): Boolean {
        val nx = x + d.delta.x
        val ny = y + d.delta.y
        if (key(nx, ny) in blocked) return false
        if (d.isCardinal()) return edge(x, y, d) !in walls
        val horizontal = d.horizontal()
        val vertical = d.vertical()
        return canStep(x, y, horizontal) && canStep(x, y, vertical) &&
            canStep(x + horizontal.delta.x, y, vertical) && canStep(x, y + vertical.delta.y, horizontal)
    }

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun edge(x: Int, y: Int, d: Direction): Long = key(x, y) * 16 + d.ordinal

    companion object {
        fun tile(x: Int, y: Int): Tile = Tile.of(x, y, 0)
    }
}
