package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.WORLD_MAP
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.WorldMapSection
import world.gregs.voidps.cache.type.data.WorldMapType

/**
 * Decodes a map area's details.
 *
 * The index carries no group names, so the details archive is addressed by its id and a file id is a
 * map area id.
 */
class WorldMapDetailsDecoder : TypeDecoder<WorldMapType>(WORLD_MAP) {

    override fun getArchive(id: Int) = DETAILS_ARCHIVE

    override fun size(cache: Cache) = cache.lastFileId(index, DETAILS_ARCHIVE)

    override fun create(size: Int) = Array(size) { WorldMapType(it) }

    override fun readLoop(definition: WorldMapType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun WorldMapType.read(opcode: Int, buffer: Reader) = Unit

    private fun WorldMapType.decode(buffer: Reader) {
        map = buffer.readString()
        name = buffer.readString()
        position = buffer.readInt()
        colour = buffer.readInt()
        static = buffer.readUnsignedBoolean()
        unknown6 = buffer.readUnsignedByte()
        unknown7 = buffer.readUnsignedByte()
        sections = List(buffer.readUnsignedByte()) {
            WorldMapSection(
                buffer.readUnsignedByte(),
                buffer.readShort(),
                buffer.readShort(),
                buffer.readShort(),
                buffer.readShort(),
                buffer.readShort(),
                buffer.readShort(),
                buffer.readShort(),
                buffer.readShort(),
            )
        }
    }

    private companion object {
        const val DETAILS_ARCHIVE = 0
    }
}
