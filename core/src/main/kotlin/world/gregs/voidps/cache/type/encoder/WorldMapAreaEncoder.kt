package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock
import world.gregs.voidps.cache.type.data.WorldMapAreaBlock.Companion.WHOLE_MAP_SQUARE
import world.gregs.voidps.cache.type.data.WorldMapAreaType
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.ESCAPE
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.INLINE_SHIFT
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.LAYERED
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.LAYER_MASK
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.LOCATIONS
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.OVERLAY
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder.Companion.SIMPLE_EXTRA_ID

class WorldMapAreaEncoder : TypeEncoder<WorldMapAreaType> {

    override fun Writer.encode(definition: WorldMapAreaType) {
        writeByte(definition.underlays.size)
        for (id in definition.underlays) writeSmart(id)
        writeByte(definition.overlays.size)
        for (id in definition.overlays) writeSmart(id)
        for (block in definition.blocks) {
            encodeBlock(block)
        }
    }

    private fun Writer.encodeBlock(block: WorldMapAreaBlock) {
        writeByte(block.tag)
        writeByte(block.mapSquareX)
        writeByte(block.mapSquareZ)
        if (block.tag == WHOLE_MAP_SQUARE) {
            for (value in block.chunkMask!!) writeByte(value)
        } else {
            writeByte(block.chunkX!!)
            writeByte(block.chunkZ!!)
            writeByte(block.value!!)
        }
        encodeCells(block.cells)
    }

    private fun Writer.encodeCells(cells: IntArray) {
        var position = 0
        while (position < cells.size) {
            val flags = cells[position++]
            writeByte(flags)
            if (flags and LAYERED == 0) {
                if (flags ushr INLINE_SHIFT == ESCAPE) {
                    writeSmart(cells[position++])
                }
                if (flags and SIMPLE_EXTRA_ID != 0) {
                    writeSmart(cells[position++])
                }
                continue
            }
            repeat(((flags shr 1) and LAYER_MASK) + 1) {
                writeSmart(cells[position++])
                if (flags and OVERLAY != 0) {
                    writeSmart(cells[position++])
                    writeByte(cells[position++])
                }
                if (flags and LOCATIONS != 0) {
                    val locations = cells[position++]
                    writeByte(locations)
                    repeat(locations) {
                        writeBigSmart(cells[position++])
                        writeByte(cells[position++])
                    }
                }
            }
        }
    }
}
