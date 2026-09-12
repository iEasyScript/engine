package world.gregs.voidps.type

import kotlin.math.atan2

enum class Direction(deltaX: Int, deltaY: Int) {
    NORTH_WEST(-1, 1),
    NORTH(0, 1),
    NORTH_EAST(1, 1),
    EAST(1, 0),
    SOUTH_EAST(1, -1),
    SOUTH(0, -1),
    SOUTH_WEST(-1, -1),
    WEST(-1, 0),
    NONE(0, 0);

    val delta = Delta(deltaX, deltaY)

    /** Clockwise index matching the engine's Direction numbering (NORTH=0 .. NORTHWEST=7); -1 for [NONE]. */
    val id: Int
        get() = when (this) {
            NORTH -> 0
            NORTH_EAST -> 1
            EAST -> 2
            SOUTH_EAST -> 3
            SOUTH -> 4
            SOUTH_WEST -> 5
            WEST -> 6
            NORTH_WEST -> 7
            NONE -> -1
        }

    /** Compass angle (0..0x3fff), matching the engine's getAngleTo formula. */
    val angle: Int
        get() = (atan2(-delta.x.toDouble(), -delta.y.toDouble()) * 2607.5945876176133).toInt() and 0x3fff

    fun isDiagonal() = delta.isHorizontal() && delta.isVertical()

    fun isCardinal(): Boolean = delta.isCardinal()

    fun isHorizontal() = delta.isHorizontal()

    fun isVertical() = delta.isVertical()

    /**
     * Rotate direction clockwise in increments of 1/8
     */
    fun rotate(count: Int): Direction {
        return all[(ordinal + count + all.size).rem(all.size)]
    }

    fun vertical(): Direction {
        return when (delta.y) {
            1 -> NORTH
            -1 -> SOUTH
            else -> NONE
        }
    }

    fun horizontal(): Direction {
        return when (delta.x) {
            1 -> EAST
            -1 -> WEST
            else -> NONE
        }
    }

    fun inverse(): Direction {
        return when (this) {
            NORTH_WEST -> SOUTH_EAST
            NORTH -> SOUTH
            NORTH_EAST -> SOUTH_WEST
            EAST -> WEST
            SOUTH_EAST -> NORTH_WEST
            SOUTH -> NORTH
            SOUTH_WEST -> NORTH_EAST
            WEST -> EAST
            NONE -> NONE
        }
    }

    companion object {
        val size = entries.size
        val cardinal = entries.filter { it.isCardinal() && it.delta.x != it.delta.y }
        val ordinal = entries.filter { it.isDiagonal() }
        val values = entries.toTypedArray()
        val reversed = entries.reversed()
        val all = entries.toTypedArray().copyOfRange(0, size - 1)
        val clockwise = arrayOf(NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST, NORTH_WEST)
        val westClockwise = arrayOf(WEST, NORTH, EAST, SOUTH)

        fun of(deltaX: Int, deltaY: Int): Direction {
            return all.firstOrNull { it.delta.equals(deltaX, deltaY) } ?: NONE
        }

        /** Engine-compatible lookup by clockwise [id] (NORTH=0 .. NORTHWEST=7); unknown ids fall back to [SOUTH]. */
        @JvmStatic
        fun getById(id: Int): Direction = when (id) {
            0 -> NORTH
            1 -> NORTH_EAST
            2 -> EAST
            3 -> SOUTH_EAST
            4 -> SOUTH
            5 -> SOUTH_WEST
            6 -> WEST
            7 -> NORTH_WEST
            else -> SOUTH
        }

        /**
         * Engine-compatible direction for a raw (possibly non-unit) delta using sign thresholds;
         * returns null when both deltas are zero.
         */
        @JvmStatic
        fun forDelta(dx: Int, dy: Int): Direction? = when {
            dy >= 1 && dx >= 1 -> NORTH_EAST
            dy <= -1 && dx >= 1 -> SOUTH_EAST
            dy <= -1 && dx <= -1 -> SOUTH_WEST
            dy >= 1 && dx <= -1 -> NORTH_WEST
            dy >= 1 -> NORTH
            dx >= 1 -> EAST
            dy <= -1 -> SOUTH
            dx <= -1 -> WEST
            else -> null
        }

        /** Engine-compatible direction from [from] toward [to]; null when the tiles coincide on the x/y plane. */
        @JvmStatic
        fun getDirectionBetween(from: Tile, to: Tile): Direction? = forDelta(to.x - from.x, to.y - from.y)

        /** Engine-compatible clockwise rotation of [dir] by [rotation] eighth-turns. */
        @JvmStatic
        fun rotateClockwise(dir: Direction, rotation: Int): Direction = dir.rotate(rotation)
    }
}