package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.VAR_BIT
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.VarBitType
import world.gregs.voidps.cache.type.encoder.VarBitEncoder

class VarBitDecoder : ConfigDecoder<VarBitType>(VAR_BIT) {
    override fun create(size: Int) = Array(size) { VarBitType(it) }

    private val encoder = VarBitEncoder()

    override fun canonicalOpcodes(definition: VarBitType): IntArray = encoder.opcodes(definition)

    override fun VarBitType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                domainId = buffer.readUnsignedByte().toByte()
                index = buffer.readBigSmart()
            }
            2 -> {
                startBit = buffer.readUnsignedByte()
                endBit = buffer.readUnsignedByte()
            }
            16 -> flags = 1
            else -> unknown(opcode, buffer)
        }
    }
}
