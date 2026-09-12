package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.SEQ_GROUP
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.SeqGroupType
import world.gregs.voidps.cache.type.encoder.SeqGroupEncoder

class SeqGroupDecoder : ConfigDecoder<SeqGroupType>(SEQ_GROUP) {

    override fun create(size: Int) = Array(size) { SeqGroupType(it) }

    private val encoder = SeqGroupEncoder()

    override fun canonicalOpcodes(definition: SeqGroupType): IntArray = encoder.opcodes(definition)

    override fun SeqGroupType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> field1 = buffer.readUnsignedShort()
            2 -> {
                val count = buffer.readUnsignedByte()
                transitions = IntArray(count) { buffer.readUnsignedShort() }
            }
            3 -> field3 = buffer.readUnsignedByte()
            4 -> field4 = buffer.readUnsignedByte()
            5 -> field5 = buffer.readBigSmart()
            6 -> field6 = buffer.readBigSmart()
            else -> unknown(opcode, buffer)
        }
    }
}
