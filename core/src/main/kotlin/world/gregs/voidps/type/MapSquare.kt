package world.gregs.voidps.type

import world.gregs.voidps.type.area.Cuboid
import world.gregs.voidps.type.area.Rectangle

/**
 * Represents a 64x64 tiled area
 */
@JvmInline
value class MapSquare(val id: Int) {

    constructor(x: Int, y: Int) : this(id(x, y))

    val x: Int
        get() = x(id)
    val y: Int
        get() = y(id)
    val tile: Tile
        get() = Tile(x shl 6, y shl 6, 0)

    fun copy(x: Int = this.x, y: Int = this.y) = MapSquare(x, y)
    fun add(x: Int, y: Int) = copy(x = this.x + x, y = this.y + y)
    fun minus(x: Int = 0, y: Int = 0) = add(-x, -y)
    fun delta(x: Int = 0, y: Int = 0) = Delta(this.x - x, this.y - y)

    fun add(point: MapSquare) = add(point.x, point.y)
    fun minus(point: MapSquare) = minus(point.x, point.y)
    fun delta(point: MapSquare) = delta(point.x, point.y)

    fun toLevel(level: Int) = MapSquareLevel(x, y, level)

    fun toRectangle(radius: Int) = Rectangle(minus(radius, radius).tile, (radius * 2 + 1) * 64, (radius * 2 + 1) * 64)
    fun toRectangle(width: Int = 1, height: Int = 1) = Rectangle(tile, width * 64, height * 64)
    fun toCuboid(width: Int = 1, height: Int = 1) = Cuboid(tile, width * 64, height * 64, 4)
    fun toCuboid(radius: Int) = Cuboid(minus(radius, radius).tile, (radius * 2 + 1) * 64, (radius * 2 + 1) * 64, 4)

    fun offset(mapSquare: MapSquare): Delta {
        return tile.delta(mapSquare.tile)
    }

    companion object {
        fun id(x: Int, y: Int) = (y and 0xff) + ((x and 0xff) shl 8)
        fun x(id: Int) = id shr 8
        fun y(id: Int) = id and 0xff
        val EMPTY = MapSquare(0, 0)
    }
}

fun MapSquare.equals(x: Int, y: Int) = this.x == x && this.y == y