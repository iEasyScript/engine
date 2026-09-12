package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.PARAMS
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.ParamType
import world.gregs.voidps.cache.type.encoder.ParamEncoder

class ParamDecoder : ConfigDecoder<ParamType>(PARAMS) {

    override fun create(size: Int) = Array(size) { ParamType(it) }

    private val encoder = ParamEncoder()

    override fun canonicalOpcodes(definition: ParamType): IntArray = encoder.opcodes(definition)

    override fun ParamType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> type = byteToChar(buffer.readByte().toByte())
            2 -> defaultInt = buffer.readInt()
            4 -> autoDisable = false
            5 -> defaultString = buffer.readString()
            101 -> typeId = buffer.readUnsignedSmart()
            else -> unknown(opcode, buffer)
        }
    }
}
