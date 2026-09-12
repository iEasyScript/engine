package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.StylesheetProperty
import world.gregs.voidps.cache.type.data.StylesheetType

class StylesheetDecoder : TypeDecoder<StylesheetType>(INDEX) {

    override fun create(size: Int) = Array(size) { StylesheetType(it) }

    override fun readLoop(definition: StylesheetType, buffer: Reader) {
        recordDecode(definition.id, buffer) {
            definition.parent = buffer.readShort()
            definition.properties = List(buffer.readUnsignedShort()) {
                StylesheetProperty(buffer.readByte(), buffer.readInt(), buffer.readInt())
            }
        }
    }

    override fun StylesheetType.read(opcode: Int, buffer: Reader) = Unit

    companion object {
        const val INDEX = 60
    }
}
