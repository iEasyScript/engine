package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.HuffmanType

class HuffmanDecoder : TypeDecoder<HuffmanType>(INDEX) {

    override fun create(size: Int) = Array(size) { HuffmanType(it) }

    override fun readLoop(definition: HuffmanType, buffer: Reader) {
        recordDecode(definition.id, buffer) {
            definition.codeLengths = IntArray(buffer.readableBytes()) { buffer.readUnsignedByte() }
        }
    }

    override fun HuffmanType.read(opcode: Int, buffer: Reader) = Unit

    companion object {
        const val INDEX = 10
    }
}
