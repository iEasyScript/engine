package world.gregs.voidps.cache.type.data

/**
 * The terrain file the NXT client actually reads: a magic header and one block per level that has
 * anything on it, each a 66 by 66 grid of cells.
 *
 * 66 is the map square's 64 tiles plus a one cell border on each side, so tile `(x, y)` is cell
 * `(x + 1, y + 1)`. Blocks carry their own level index and only exist for levels with content, so a
 * file holds one to four of them, ascending.
 */
class MapSquareTerrain {

    /** The version of the file's magic header, or [MapSquareTiles.NO_HEADER] when it carries none. */
    var version: Int = MapSquareTiles.NO_HEADER

    val levels: MutableList<MapSquareTerrainLevel> = mutableListOf()
}

/**
 * One level's grid, as parallel arrays indexed `x * SIZE + y`.
 *
 * [populated] is the cell's flag byte being non-zero: a cell that is not populated carries a height
 * and nothing else, which is what makes the flag byte derivable rather than stored. [secondLayer]
 * is the flag bit that adds a second set of heights and ids. An id of [NONE] is a record the file
 * stored as absent, which is not the same as id 0.
 */
class MapSquareTerrainLevel(val level: Int) {
    val populated = BooleanArray(CELLS)
    val secondLayer = BooleanArray(CELLS)
    val settings = IntArray(CELLS)

    /** Absolute on level 0 and added to the level below above it. */
    val heights = IntArray(CELLS)
    val secondHeights = IntArray(CELLS)
    val underlayIds = IntArray(CELLS)

    /** Present only where the underlay id is not [NONE]; the client treats it as opaque. */
    val underlayColours = IntArray(CELLS)
    val overlayIds = IntArray(CELLS)
    val overlayShapes = IntArray(CELLS)
    val overlayRotations = IntArray(CELLS)
    val secondOverlayIds = IntArray(CELLS)
    val secondUnderlayIds = IntArray(CELLS)

    companion object {
        /** The map square's 64 tiles plus a one cell border on each side. */
        const val SIZE = 66

        const val CELLS = SIZE * SIZE

        const val NONE = -1

        fun index(x: Int, y: Int): Int = x * SIZE + y
    }
}
