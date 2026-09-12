package world.gregs.voidps.type

import world.gregs.voidps.type.area.Cuboid
import java.security.SecureRandom
import kotlin.math.sqrt

@JvmInline
value class Tile(val id: Int) : Coordinate3D<Tile> {

    constructor(x: Int, y: Int, level: Int = 0) : this(id(x, y, level))

    override val x: Int
        get() = x(id)
    override val y: Int
        get() = y(id)
    override val level: Int
        get() = level(id)

    val zone: Zone
        get() = Zone(x shr 3, y shr 3, level)
    val mapSquare: MapSquare
        get() = MapSquare(x shr 6, y shr 6)
    val mapSquareLevel: MapSquareLevel
        get() = MapSquareLevel(x shr 6, y shr 6, level)

    fun getCoordFaceX(sizeX: Int, sizeY: Int, rotation: Int) = x + ((if (rotation == 1 || rotation == 3) sizeY else sizeX) - 1) / 2
    fun getCoordFaceX(sizeX: Int) = getCoordFaceX(-1, sizeX, -1)
    fun getCoordFaceY(sizeX: Int, sizeY: Int, rotation: Int) = y + ((if (rotation == 1 || rotation == 3) sizeX else sizeY) - 1) / 2
    fun getCoordFaceY(sizeY: Int) = getCoordFaceY(-1, sizeY, -1)

    override fun copy(x: Int, y: Int, level: Int) = Tile(x, y, level)

    fun distanceTo(other: Tile, width: Int, height: Int) = distanceTo(Distance.getNearest(other, width, height, this))

    fun distanceTo(other: Tile): Int {
        if (level != other.level) {
            return -1
        }
        return Distance.chebyshev(x, y, other.x, other.y)
    }

    fun within(other: Tile, radius: Int): Boolean {
        return Distance.within(x, y, level, other.x, other.y, other.level, radius)
    }

    fun within(x: Int, y: Int, level: Int, radius: Int): Boolean {
        return Distance.within(this.x, this.y, this.level, x, y, level, radius)
    }

    fun toCuboid(width: Int = 1, height: Int = 1) = Cuboid(this, width, height, 1)
    fun toCuboid(radius: Int) = Cuboid(minus(radius, radius), radius * 2 + 1, radius * 2 + 1, 1)

    fun transform(dx: Int, dy: Int, dl: Int): Tile = add(dx, dy, dl)

    fun transform(dx: Int, dy: Int): Tile = transform(dx, dy, 0)

    /** Chebyshev box check, plane-aware (false if planes differ). */
    fun withinDistance(other: Tile, distance: Int) = within(other, distance)

    /** Default distance of 20 matches engine default. */
    fun withinDistance(other: Tile) = withinDistance(other, 20)

    /** Euclidean distance, truncated to an Int. Plane-agnostic, matching engine semantics. */
    fun getDistance(other: Tile): Int {
        val dx = other.x - x
        val dy = other.y - y
        return sqrt((dx * dx + dy * dy).toDouble()).toInt()
    }

    /** Backward compatibility with legacy code that uses 'plane'. */
    val plane: Int get() = level

    val mapSquareX: Int
        get() = x shr 6

    val mapSquareY: Int
        get() = y shr 6

    val zoneX: Int
        get() = x shr 3

    val zoneY: Int
        get() = y shr 3

    val xInMapSquare: Int
        get() = x and 63

    val yInMapSquare: Int
        get() = y and 63

    val xInZone: Int
        get() = x and 7

    val yInZone: Int
        get() = y and 7

    val mapSquareId: Int
        get() = mapSquare.id

    val mapSquareHash: Int
        get() = mapSquareY + (mapSquareX shl 8) + (plane shl 16)

    val tileHash: Int
        get() = id

    val zoneId: Int
        get() = (zoneX shl 11) or zoneY or (plane shl 22)

    val zoneLocalHash: Int
        get() = (xInZone shl 4) or yInZone

    fun matches(other: Tile) = id == other.id

    fun getXInScene(baseZoneId: Int): Int {
        val baseX = (baseZoneId shr 14 and 0x3fff) shl 3
        return x - baseX
    }

    fun getYInScene(baseZoneId: Int): Int {
        val baseY = (baseZoneId and 0x3fff) shl 3
        return y - baseY
    }

    fun getZoneXInScene(baseZoneId: Int): Int {
        val baseZoneX = baseZoneId shr 14 and 0x3fff
        return (x shr 3) - baseZoneX
    }

    fun getZoneYInScene(baseZoneId: Int): Int {
        val baseZoneY = baseZoneId and 0x3fff
        return (y shr 3) - baseZoneY
    }

    fun getLongestDelta(other: Tile): Int {
        val dx = Math.abs(x - other.x)
        val dy = Math.abs(y - other.y)
        return maxOf(dx, dy)
    }

    /** Used for cutscene coordinate normalization. */
    fun localizeMapSquare(): Tile = Tile(x and 63, y and 63, level)

    fun withinArea(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        x >= x1 && x <= x2 && y >= y1 && y <= y2

    fun isAt(x: Int, y: Int) = this.x == x && this.y == y

    fun isAt(x: Int, y: Int, level: Int) = this.x == x && this.y == y && this.level == level

    fun randomize(range: Int) = of(this, range)

    fun randomizeX(range: Int) = Tile(x + tileRandom.nextInt(range * 2 + 1) - range, y, level)

    fun randomizeY(range: Int) = Tile(x, y + tileRandom.nextInt(range * 2 + 1) - range, level)

    operator fun component1() = x
    operator fun component2() = y
    operator fun component3() = level

    override fun toString(): String {
        return "Tile($x, $y, $level)"
    }

    companion object {
        fun id(x: Int, y: Int, level: Int) = (y and 0x3fff) + ((x and 0x3fff) shl 14) + ((level.coerceIn(0, 3)) shl 28)

        fun id(x: Int, y: Int) = id(x, y, 0)

        fun x(id: Int) = id shr 14 and 0x3fff

        fun y(id: Int) = id and 0x3fff

        fun level(id: Int) = id shr 28 and 0x3

        val EMPTY = Tile(0)

        fun of(x: Int, y: Int, level: Int) = Tile(x, y, level)

        fun of(x: Int, y: Int) = of(x, y, 0)

        /** Legacy RS tile format: plane, mapSquareX, mapSquareY, localX, localY. */
        fun of(plane: Int, mapSquareX: Int, mapSquareY: Int, localX: Int, localY: Int) =
            Tile(mapSquareX * 64 + localX, mapSquareY * 64 + localY, plane)

        fun toInt(x: Int, y: Int, level: Int): Int = Tile(x, y, level).id

        /** Applies a centre face-offset for an object/entity of the given [size], matching engine's Tile.of(x, y, plane, size). */
        fun of(x: Int, y: Int, level: Int, size: Int) = Tile(getCoordFaceX(x, size, size, -1), getCoordFaceY(y, size, size, -1), level)

        fun getCoordFaceX(x: Int, sizeX: Int, sizeY: Int, rotation: Int) = x + ((if (rotation == 1 || rotation == 3) sizeY else sizeX) - 1) / 2

        fun getCoordFaceY(y: Int, sizeX: Int, sizeY: Int, rotation: Int) = y + ((if (rotation == 1 || rotation == 3) sizeX else sizeY) - 1) / 2

        fun of(id: Int) = Tile(id)

        fun of(tile: Tile) = Tile(tile.id)

        private val tileRandom = SecureRandom()

        fun of(tile: Tile, randomize: Int): Tile {
            val dx = tileRandom.nextInt(randomize * 2 + 1) - randomize
            val dy = tileRandom.nextInt(randomize * 2 + 1) - randomize
            return Tile(tile.x + dx, tile.y + dy, tile.level)
        }

        fun fromMap(map: Map<String, Any>) = Tile(map["x"] as Int, map["y"] as Int, map["level"] as? Int ?: 0)
        fun fromArray(array: IntArray) = Tile(array[0], array[1], array.getOrNull(2) ?: 0)
        fun fromArray(array: List<Int>) = Tile(array[0], array[1], array.getOrNull(2) ?: 0)

        fun index(x: Int, y: Int): Int = (x and 0x7) or ((y and 0x7) shl 3)
        fun index(x: Int, y: Int, layer: Int): Int = index(x, y) or ((layer and 0x7) shl 6)
        fun indexX(index: Int) = index and 0x7
        fun indexY(index: Int) = index shr 3 and 0x7
        fun indexLayer(index: Int) = index shr 6 and 0x7
    }
}

fun Tile.equals(x: Int = this.x, y: Int = this.y, level: Int = this.level) = this.x == x && this.y == y && this.level == level
