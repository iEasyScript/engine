package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.CURSORS
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.CursorType
import world.gregs.voidps.cache.type.encoder.CursorEncoder

class CursorDecoder : ConfigDecoder<CursorType>(CURSORS) {

    override fun create(size: Int) = Array(size) { CursorType(it) }

    private val encoder = CursorEncoder()

    override fun canonicalOpcodes(definition: CursorType): IntArray = encoder.opcodes(definition)

    override fun CursorType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> graphicId = buffer.readBigSmart()
            2 -> {
                hotspotX = buffer.readUnsignedByte()
                hotspotY = buffer.readUnsignedByte()
            }
            else -> unknown(opcode, buffer)
        }
    }
}
