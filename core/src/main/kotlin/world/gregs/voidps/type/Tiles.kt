package world.gregs.voidps.type

/**
 * Java-friendly factory and utility methods for [Tile].
 *
 * The [Tile] inline value class has mangled JVM method names for any function
 * that takes or returns [Tile]. This object provides non-mangled static methods
 * by accepting/returning boxed [Any] so Java code can call them with a cast.
 *
 * Usage from Java:
 * ```java
 * Tile tile = Tiles.of(3200, 3200, 0);
 * Tile copy = Tiles.of(existingTile);
 * Tile moved = Tiles.transform(tile, 1, 0, 0);
 * ```
 */
@Suppress("NOTHING_TO_INLINE")
object Tiles {

    @JvmStatic
    inline fun of(x: Int, y: Int, level: Int): Any = Tile(x, y, level)

    @JvmStatic
    inline fun of(x: Int, y: Int): Any = Tile(x, y, 0)

    @JvmStatic
    inline fun ofId(id: Int): Any = Tile(id)

    @JvmStatic
    fun copy(tile: Any): Any {
        val t = tile as Tile
        return Tile(t.id)
    }

    @JvmStatic
    fun ofRandom(tile: Any, randomize: Int): Any {
        return Tile.of(tile as Tile, randomize)
    }

    @JvmStatic
    fun transform(tile: Any, dx: Int, dy: Int, dl: Int): Any {
        return (tile as Tile).add(dx, dy, dl)
    }

    @JvmStatic
    fun transform(tile: Any, dx: Int, dy: Int): Any {
        return (tile as Tile).add(dx, dy, 0)
    }

    @JvmStatic
    fun getXInScene(tile: Any, baseZoneId: Int): Int {
        val t = tile as Tile
        val baseX = (baseZoneId shr 14 and 0x3fff) shl 3
        return t.x - baseX
    }

    @JvmStatic
    fun getYInScene(tile: Any, baseZoneId: Int): Int {
        val t = tile as Tile
        val baseY = (baseZoneId and 0x3fff) shl 3
        return t.y - baseY
    }

    @JvmStatic
    fun getZoneX(tile: Any): Int = (tile as Tile).x shr 3

    @JvmStatic
    fun getZoneY(tile: Any): Int = (tile as Tile).y shr 3

    @JvmStatic
    fun isAt(tile: Any, x: Int, y: Int): Boolean {
        val t = tile as Tile
        return t.x == x && t.y == y
    }
}
