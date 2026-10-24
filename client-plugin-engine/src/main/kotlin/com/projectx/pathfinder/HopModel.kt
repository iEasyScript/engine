package com.projectx.pathfinder

import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Where Surge and Dive land, by the game's trajectory rule as modelled by Cluetrainer (movement.ts, MIT, (c) 2024
 * Lukas Gail): a hop does not fly a straight line. Each step takes the diagonal toward the target when that step is
 * legal, else the x step, else the y step, never leaving the rectangle spanned by start and target, so a hop slides
 * round a building corner. Replayed against 5,030 community hops it reproduced 99.7 % of them.
 */
internal object HopModel {
    const val VERSION = 6
    const val SURGE_RANGE = 10
    const val DIVE_RANGE = 10

    /** The greedy walk from [from] to [target]: [target] when reached, null when a step has no legal choice. */
    fun greedy(rule: StepRule, from: Tile, target: Tile, path: MutableList<Tile>? = null): Tile? {
        if (from.plane != target.plane) return null
        var x = from.x
        var y = from.y
        val plane = from.plane
        val sx = (target.x - x).sign
        val sy = (target.y - y).sign
        val minX = min(x, target.x)
        val maxX = max(x, target.x)
        val minY = min(y, target.y)
        val maxY = max(y, target.y)
        val diagonal = if (sx != 0 && sy != 0) direction(sx, sy) else null
        val horizontal = if (sx != 0) direction(sx, 0) else null
        val vertical = if (sy != 0) direction(0, sy) else null
        while (x != target.x || y != target.y) {
            val step = when {
                diagonal != null && inside(x + sx, y + sy, minX, maxX, minY, maxY) && rule.canStep(x, y, plane, diagonal) -> diagonal
                horizontal != null && inside(x + sx, y, minX, maxX, minY, maxY) && rule.canStep(x, y, plane, horizontal) -> horizontal
                vertical != null && inside(x, y + sy, minX, maxX, minY, maxY) && rule.canStep(x, y, plane, vertical) -> vertical
                else -> return null
            }
            x += step.delta.x
            y += step.delta.y
            path?.add(Tile.of(x, y, plane))
        }
        return Tile.of(target.x, target.y, plane)
    }

    /** The furthest tile [range]..1 along [direction] that [greedy] reaches; [from] when none is. */
    fun ray(rule: StepRule, from: Tile, direction: Direction, range: Int, path: MutableList<Tile>? = null): Tile {
        if (direction == Direction.NONE) return from
        for (i in range downTo 1) {
            val target = Tile.of(from.x + direction.delta.x * i, from.y + direction.delta.y * i, from.plane)
            val mark = path?.size ?: 0
            greedy(rule, from, target, path)?.let { return it }
            path?.subList(mark, path.size)?.clear()
        }
        return from
    }

    fun surge(rule: StepRule, from: Tile, direction: Direction, path: MutableList<Tile>? = null): Tile =
        ray(rule, from, direction, SURGE_RANGE, path)

    /**
     * A dive clicked on [aim]: within reach the greedy walk to it, null when refused ("You can't dive through
     * something you can't walk through!"); further out the click snaps to one of the 8 ways and the dive goes as far
     * along that ray as it can, null when not even one tile.
     */
    fun dive(rule: StepRule, from: Tile, aim: Tile, path: MutableList<Tile>? = null): Tile? {
        if (from.plane != aim.plane || from == aim) return null
        val dx = aim.x - from.x
        val dy = aim.y - from.y
        if (max(abs(dx), abs(dy)) <= DIVE_RANGE) return greedy(rule, from, aim, path)
        val landing = ray(rule, from, dir8(dx, dy) ?: return null, DIVE_RANGE, path)
        return if (landing == from) null else landing
    }

    fun isFarAim(from: Tile, aim: Tile): Boolean = from.getLongestDelta(aim) > DIVE_RANGE

    fun direction(dx: Int, dy: Int): Direction? = Steps.of(dx, dy)

    /** Bearing in degrees, 0 north and 90 east. */
    fun bearing(direction: Direction): Double {
        val degrees = Math.toDegrees(atan2(direction.delta.x.toDouble(), direction.delta.y.toDouble()))
        return if (degrees < 0) degrees + 360.0 else degrees
    }

    fun bearing(from: Tile, to: Tile): Double {
        val degrees = Math.toDegrees(atan2((to.x - from.x).toDouble(), (to.y - from.y).toDouble()))
        return if (degrees < 0) degrees + 360.0 else degrees
    }

    /** The nearest of the 8 ways to a bearing; null for a negative one. */
    fun fromBearing(degrees: Double): Direction? {
        if (degrees < 0) return null
        return Direction.clockwise[(floor(((degrees % 360.0) + 22.5) / 45.0).toInt()) % 8]
    }

    /**
     * The 8-way direction the game gives an offset: diagonal when twice the smaller component is strictly more than
     * the larger, else the larger axis. Measured with standing far dives; Cluetrainer's click table disagrees at
     * exactly that boundary, so it is not used.
     */
    fun dir8(dx: Int, dy: Int): Direction? {
        if (dx == 0 && dy == 0) return null
        val ax = abs(dx)
        val ay = abs(dy)
        if (2 * min(ax, ay) > max(ax, ay)) return direction(dx.sign, dy.sign)
        return if (ax > ay) direction(dx.sign, 0) else direction(0, dy.sign)
    }

    private fun inside(x: Int, y: Int, minX: Int, maxX: Int, minY: Int, maxY: Int) = x in minX..maxX && y in minY..maxY
}
