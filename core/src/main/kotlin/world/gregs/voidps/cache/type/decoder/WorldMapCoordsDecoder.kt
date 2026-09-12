package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.WORLD_MAP
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.WorldMapCoordsType
import world.gregs.voidps.cache.type.data.WorldMapSquareLink
import world.gregs.voidps.cache.type.data.WorldMapZoneLink

/** Decodes a map area's coordinate links; a file id is a map area id, as in the details archive. */
class WorldMapCoordsDecoder : TypeDecoder<WorldMapCoordsType>(WORLD_MAP) {

    override fun getArchive(id: Int) = COORDS_ARCHIVE

    override fun size(cache: Cache) = cache.lastFileId(index, COORDS_ARCHIVE)

    override fun create(size: Int) = Array(size) { WorldMapCoordsType(it) }

    override fun readLoop(definition: WorldMapCoordsType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun WorldMapCoordsType.read(opcode: Int, buffer: Reader) = Unit

    private fun WorldMapCoordsType.decode(buffer: Reader) {
        squares = List(buffer.readUnsignedShort()) {
            WorldMapSquareLink(
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedShort(),
            )
        }
        zones = List(buffer.readUnsignedShort()) {
            WorldMapZoneLink(
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedShort(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
            )
        }
        width = buffer.readUnsignedByte()
        height = buffer.readUnsignedByte()
    }

    private companion object {
        const val COORDS_ARCHIVE = 1
    }
}
