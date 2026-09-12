package world.gregs.voidps.cache.type.decoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Index.WORLD_MAP_AREAS
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock.Companion.CHUNK_CELLS
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock.Companion.CHUNK_MASK_BYTES
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock.Companion.MAP_SQUARE_CELLS
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock.Companion.WHOLE_MAP_SQUARE
import world.gregs.voidps.cache.type.data.WorldMapAreaType

/**
 * A map area's tile data - the first file of an area group.
 *
 * There is no record count and no length prefix: the record loop runs until the buffer is spent.
 * A cell's leading byte selects everything after it, so [readCells] flattens flag and values into
 * one array and nothing about a cell is lost.
 */
class WorldMapAreaDecoder : TypeDecoder<WorldMapAreaType>(WORLD_MAP_AREAS) {

    override fun create(size: Int) = Array(size) { WorldMapAreaType(it) }

    override fun getFile(id: Int) = AREA_FILE

    override fun readLoop(definition: WorldMapAreaType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun WorldMapAreaType.read(opcode: Int, buffer: Reader) = Unit

    fun decode(id: Int, data: ByteArray): WorldMapAreaType =
        WorldMapAreaType(id).also { it.decode(BufferReader(data)) }

    private fun WorldMapAreaType.decode(buffer: Reader) {
        underlays = IntArray(buffer.readUnsignedByte()) { buffer.readSmart() }
        overlays = IntArray(buffer.readUnsignedByte()) { buffer.readSmart() }
        val records = ArrayList<WorldMapAreaBlock>()
        while (buffer.readableBytes() > 0) {
            records.add(readBlock(buffer))
        }
        blocks = records
    }

    private fun readBlock(buffer: Reader): WorldMapAreaBlock {
        val block = WorldMapAreaBlock(tag = buffer.readUnsignedByte())
        block.mapSquareX = buffer.readUnsignedByte()
        block.mapSquareZ = buffer.readUnsignedByte()
        if (block.tag == WHOLE_MAP_SQUARE) {
            block.chunkMask = IntArray(CHUNK_MASK_BYTES) { buffer.readUnsignedByte() }
            block.cells = readCells(buffer, MAP_SQUARE_CELLS)
            return block
        }
        block.chunkX = buffer.readUnsignedByte()
        block.chunkZ = buffer.readUnsignedByte()
        block.value = buffer.readUnsignedByte()
        block.cells = readCells(buffer, CHUNK_CELLS)
        return block
    }

    private fun readCells(buffer: Reader, count: Int): IntArray {
        val cells = IntArrayList(count)
        repeat(count) { readCell(buffer, cells) }
        return cells.toIntArray()
    }

    private fun readCell(buffer: Reader, cells: IntArrayList) {
        val flags = buffer.readUnsignedByte()
        cells.add(flags)
        if (flags and LAYERED == 0) {
            if (flags ushr INLINE_SHIFT == ESCAPE) {
                cells.add(buffer.readSmart())
            }
            if (flags and SIMPLE_EXTRA_ID != 0) {
                cells.add(buffer.readSmart())
            }
            return
        }
        repeat(((flags shr 1) and LAYER_MASK) + 1) {
            cells.add(buffer.readSmart())
            if (flags and OVERLAY != 0) {
                cells.add(buffer.readSmart())
                cells.add(buffer.readUnsignedByte())
            }
            if (flags and LOCATIONS != 0) {
                val locations = buffer.readUnsignedByte()
                cells.add(locations)
                repeat(locations) {
                    cells.add(buffer.readBigSmart())
                    cells.add(buffer.readUnsignedByte())
                }
            }
        }
    }

    companion object {
        const val AREA_FILE = 0

        internal const val LAYERED = 0x1

        internal const val SIMPLE_EXTRA_ID = 0x2

        internal const val OVERLAY = 0x8

        internal const val LOCATIONS = 0x10

        internal const val INLINE_SHIFT = 2

        internal const val LAYER_MASK = 0x3

        /** The inline field value that says the id could not be reached through the palettes. */
        internal const val ESCAPE = 0x3f
    }
}
