package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * One record of a map area's tile stream: a whole map square, or a single chunk of one.
 *
 * [tag] is the record's only selector. Zero means a whole 64 by 64 map square, whose membership in
 * the area is [chunkMask] - byte `i` bit `j` marks chunk `(x * 8 + i, z * 8 + j)` - followed by 4096
 * cells. Anything else means one 8 by 8 chunk at [chunkX], [chunkZ] whose membership is [value],
 * followed by 64 cells.
 *
 * [cells] is the cell stream flattened: each cell contributes its flag byte and then, in the order
 * the file stores them, every value that flag selects. The flag says how many values follow, so the
 * array parses back without any separator - see [world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder].
 */
data class WorldMapAreaBlock(
    var tag: Int = 0,
    var mapSquareX: Int = 0,
    var mapSquareZ: Int = 0,
    var chunkMask: IntArray? = null,
    var chunkX: Int? = null,
    var chunkZ: Int? = null,
    var value: Int? = null,
    var cells: IntArray = IntArray(0),
) {

    companion object {
        const val WHOLE_MAP_SQUARE = 0

        const val CHUNK_MASK_BYTES = 8

        const val MAP_SQUARE_CELLS = 4096

        const val CHUNK_CELLS = 64
    }
}

/**
 * A world map area's tile data: two config id palettes the cells index into, then map square and
 * chunk records to the end of the buffer.
 *
 * [underlays] holds floor underlay ids and [overlays] floor overlay ids; a cell's inline six bit
 * field is a one based index into the two concatenated, and zero means none.
 */
data class WorldMapAreaType(
    override var id: Int = -1,
    var underlays: IntArray = IntArray(0),
    var overlays: IntArray = IntArray(0),
    var blocks: List<WorldMapAreaBlock> = emptyList(),
) : CacheType
