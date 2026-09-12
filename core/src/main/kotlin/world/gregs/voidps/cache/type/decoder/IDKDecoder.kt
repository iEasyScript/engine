package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.IDENTITY_KIT
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.IDKType
import world.gregs.voidps.cache.type.encoder.IDKEncoder

class IDKDecoder : ConfigDecoder<IDKType>(IDENTITY_KIT) {

    override fun create(size: Int) = Array(size) { IDKType(it) }

    private val encoder = IDKEncoder()

    override fun canonicalOpcodes(definition: IDKType): IntArray = encoder.opcodes(definition)

    override fun IDKType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> bodyPart = buffer.readUnsignedByte()
            2 -> {
                val count = buffer.readUnsignedByte()
                modelIds = IntArray(count) { buffer.readBigSmart() }
            }
            3 -> nonSelectable = true
            40 -> {
                val count = buffer.readUnsignedByte()
                recolorSrc = IntArray(count)
                recolorDst = IntArray(count)
                for (i in 0 until count) {
                    recolorSrc!![i] = buffer.readUnsignedShort()
                    recolorDst!![i] = buffer.readUnsignedShort()
                }
            }
            41 -> {
                val count = buffer.readUnsignedByte()
                retextureSrc = IntArray(count)
                retextureDst = IntArray(count)
                for (i in 0 until count) {
                    retextureSrc!![i] = buffer.readUnsignedShort()
                    retextureDst!![i] = buffer.readUnsignedShort()
                }
            }
            44, 45 -> buffer.readShort()
            in 60..64 -> headModelIds[opcode - 60] = buffer.readBigSmart()
            else -> unknown(opcode, buffer)
        }
    }
}
