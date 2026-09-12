package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.EffectAnimType
import world.gregs.voidps.cache.type.encoder.EffectAnimEncoder

class EffectAnimDecoder : ConfigDecoder<EffectAnimType>(ARCHIVE) {

    override fun create(size: Int) = Array(size) { EffectAnimType(it) }

    private val encoder = EffectAnimEncoder()

    override fun canonicalOpcodes(definition: EffectAnimType): IntArray = encoder.opcodes(definition)

    override fun EffectAnimType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> mode = buffer.readUnsignedByte()
            2 -> angle = buffer.readUnsignedShort()
            3 -> magnitude = buffer.readUnsignedShort()
            4 -> signedMagnitude = buffer.readShort()
            5 -> floatA = buffer.readFloat()
            6 -> floatB = buffer.readFloat()
            7 -> floatC = buffer.readFloat()
            8 -> floatD = buffer.readFloat()
            9 -> flag = true
            10 -> valueA = buffer.readInt()
            11 -> valueB = buffer.readInt()
            12 -> floatE = buffer.readFloat()
            13 -> floatF = buffer.readFloat()
            else -> unknown(opcode, buffer)
        }
    }

    companion object {
        const val ARCHIVE = 31
    }
}
