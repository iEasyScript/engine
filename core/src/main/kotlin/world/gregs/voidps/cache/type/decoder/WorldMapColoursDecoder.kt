package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.WORLD_MAP
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.WorldMapColourRun
import world.gregs.voidps.cache.type.data.WorldMapColoursType
import world.gregs.voidps.cache.type.data.WorldmapSquareType.Companion.ZONES_PER_SQUARE

/**
 * Decodes one map square's world area colours; a file id is a map square id.
 *
 * The last run carries no length byte, so the run that ends the file is however many zones are left.
 */
class WorldMapColoursDecoder : TypeDecoder<WorldMapColoursType>(WORLD_MAP) {

    override fun getArchive(id: Int) = COLOURS_ARCHIVE

    override fun size(cache: Cache) = cache.lastFileId(index, COLOURS_ARCHIVE)

    override fun create(size: Int) = Array(size) { WorldMapColoursType(it) }

    override fun readLoop(definition: WorldMapColoursType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun WorldMapColoursType.read(opcode: Int, buffer: Reader) = Unit

    private fun WorldMapColoursType.decode(buffer: Reader) {
        val runs = ArrayList<WorldMapColourRun>()
        var filled = 0
        while (buffer.readableBytes() > 0) {
            val colour = buffer.readUnsignedMedium()
            val length = if (buffer.readableBytes() > 0) buffer.readUnsignedByte() else ZONES_PER_SQUARE - filled
            filled += length
            runs.add(WorldMapColourRun(colour, length))
        }
        this.runs = runs
    }

    companion object {
        const val COLOURS_ARCHIVE = 3
    }
}
