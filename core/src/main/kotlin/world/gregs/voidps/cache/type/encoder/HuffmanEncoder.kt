package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.HuffmanType

class HuffmanEncoder : TypeEncoder<HuffmanType> {

    override fun Writer.encode(definition: HuffmanType) {
        for (length in definition.codeLengths) {
            writeByte(length)
        }
    }
}
