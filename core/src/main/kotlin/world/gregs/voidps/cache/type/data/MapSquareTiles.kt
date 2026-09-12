package world.gregs.voidps.cache.type.data

/**
 * The `levels * 64 * 64` tile records of one terrain file, as parallel grids.
 *
 * Grids rather than a tile object each because a surface square is 16,384 tiles and every caller
 * wants one field of them. [records] is the flag byte a tile was decoded from, which is what makes
 * the grids lossless: a field at 0 because its bit was clear and a field the file actually stored as
 * 0 are different bytes, and only the flag byte tells them apart.
 */
class MapSquareTiles(val levels: Int) {
    val records: Array<Array<IntArray>> = grid()
    val overlayIds: Array<Array<IntArray>> = grid()
    val underlayIds: Array<Array<IntArray>> = grid()
    val heights: Array<Array<IntArray>> = grid()
    val overlayPathShapes: Array<Array<IntArray>> = grid()
    val overlayRotations: Array<Array<IntArray>> = grid()
    val flags: Array<Array<IntArray>> = grid()

    /** The version of the tile file's magic header, or [NO_HEADER] when it carries none. */
    var version: Int = NO_HEADER

    /**
     * The eight bytes the environment block opens with, or null when the file ends with its tiles.
     * Only a surface terrain file has one.
     */
    var environmentHead: IntArray? = null

    /** The environment records behind the tiles, in file order. Only a surface terrain file has any. */
    val effects: MutableList<MapSquareEffect> = mutableListOf()

    private fun grid(): Array<Array<IntArray>> = Array(levels) { Array(SIZE) { IntArray(SIZE) } }

    companion object {
        const val SIZE = 64

        const val NO_HEADER = -1

        const val OVERLAY = 0x1
        const val SETTINGS = 0x2
        const val UNDERLAY = 0x4
        const val HEIGHT = 0x8
    }
}
