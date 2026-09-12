package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.VarDomainType
import world.gregs.voidps.cache.type.encoder.VarDomainEncoder

abstract class VarDomainDecoder<T : VarDomainType>(archive: Int) : ConfigDecoder<T>(archive) {

    private val encoder = VarDomainEncoder<T>()

    override fun canonicalOpcodes(definition: T): IntArray = encoder.opcodes(definition)

    override fun T.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            3 -> type = buffer.readUnsignedByte()
            4 -> lifetime = buffer.readUnsignedByte()
            5 -> transmit = buffer.readUnsignedByte()
            7, 8 -> { }
            110 -> keep(opcode, buffer.capture { readShort() })
            else -> unknown(opcode, buffer)
        }
    }
}
