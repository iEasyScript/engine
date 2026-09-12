package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Index.WORLD_MAP_AREAS
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.WorldMapAreaElement
import world.gregs.voidps.cache.type.data.WorldMapAreaElementsType

/** A map area's map element list, the second file of an area group and the whole of its coord group. */
class WorldMapAreaElementsDecoder : TypeDecoder<WorldMapAreaElementsType>(WORLD_MAP_AREAS) {

    override fun create(size: Int) = Array(size) { WorldMapAreaElementsType(it) }

    override fun getFile(id: Int) = ELEMENTS_FILE

    override fun readLoop(definition: WorldMapAreaElementsType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun WorldMapAreaElementsType.read(opcode: Int, buffer: Reader) = Unit

    fun decode(id: Int, data: ByteArray): WorldMapAreaElementsType =
        WorldMapAreaElementsType(id).also { it.decode(BufferReader(data)) }

    private fun WorldMapAreaElementsType.decode(buffer: Reader) {
        elements = List(buffer.readUnsignedShort()) {
            WorldMapAreaElement(buffer.readInt(), buffer.readUnsignedShort(), buffer.readUnsignedByte())
        }
    }

    private companion object {
        const val ELEMENTS_FILE = 1
    }
}
