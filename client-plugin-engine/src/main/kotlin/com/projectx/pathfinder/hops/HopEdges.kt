package com.projectx.pathfinder.hops

import world.gregs.voidps.type.Direction

/**
 * Keys of hop knowledge. An edge is one step between two tiles; a wall blocks both directions, so a step and its
 * reverse are one edge, kept as the N, NE, E or SE step. Packed as `x shl 32 or y shl 16 or plane shl 4 or dir`.
 *
 * String keys, the store format: `pe:x:y:plane:DIR` - a hop crosses this edge though walking cannot; `se:x:y:plane:DIR`
 * - a hop stops at this edge though walking passes it.
 */
internal object HopEdges {
    const val PASS = "pe"
    const val STOP = "se"

    private val NAMES = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun pack(x: Int, y: Int, plane: Int, direction: Direction): Long {
        var ex = x
        var ey = y
        var d = direction
        if (direction == Direction.SOUTH || direction == Direction.WEST || direction == Direction.SOUTH_WEST || direction == Direction.NORTH_WEST) {
            ex += direction.delta.x
            ey += direction.delta.y
            d = direction.inverse()
        }
        return ((ex.toLong() and 0xFFFF) shl 32) or ((ey.toLong() and 0xFFFF) shl 16) or ((plane.toLong() and 0xF) shl 4) or d.id.toLong()
    }

    fun x(edge: Long): Int = ((edge ushr 32) and 0xFFFF).toInt()

    fun y(edge: Long): Int = ((edge ushr 16) and 0xFFFF).toInt()

    fun plane(edge: Long): Int = ((edge ushr 4) and 0xF).toInt()

    fun direction(edge: Long): Direction = Direction.getById((edge and 0xF).toInt())

    fun key(kind: String, edge: Long): String = "$kind:${x(edge)}:${y(edge)}:${plane(edge)}:${NAMES[direction(edge).id]}"

    /** The packed edge of a `pe:` or `se:` key, or -1 when it is not one. */
    fun parse(key: String): Long {
        val parts = key.split(':')
        if (parts.size != 5 || (parts[0] != PASS && parts[0] != STOP)) return -1
        val dir = NAMES.indexOf(parts[4])
        if (dir < 0) return -1
        val x = parts[1].toIntOrNull() ?: return -1
        val y = parts[2].toIntOrNull() ?: return -1
        val plane = parts[3].toIntOrNull() ?: return -1
        return pack(x, y, plane, Direction.getById(dir))
    }

    fun name(direction: Direction): String = NAMES[direction.id]

    fun directionNamed(name: String): Direction? = NAMES.indexOf(name).takeIf { it >= 0 }?.let(Direction::getById)

    fun describe(edge: Long): String = "(${x(edge)},${y(edge)},${plane(edge)})->${NAMES[direction(edge).id]}"
}
