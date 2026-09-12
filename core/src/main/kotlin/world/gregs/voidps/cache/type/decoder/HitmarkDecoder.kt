package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.HIT_SPLATS
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.HitmarkType
import world.gregs.voidps.cache.type.encoder.HitmarkEncoder

class HitmarkDecoder : ConfigDecoder<HitmarkType>(HIT_SPLATS) {

    override fun create(size: Int) = Array(size) { HitmarkType(it) }

    private val encoder = HitmarkEncoder()

    override fun canonicalOpcodes(definition: HitmarkType): IntArray = encoder.opcodes(definition)

    override fun HitmarkType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> field1 = buffer.readBigSmart()
            2 -> {
                colour = buffer.readUnsignedMedium()
                hasColour = true
            }
            3 -> field3 = buffer.readBigSmart()
            4 -> field4 = buffer.readBigSmart()
            5 -> field5 = buffer.readBigSmart()
            6 -> field6 = buffer.readBigSmart()
            7 -> field7 = buffer.readShort()
            8 -> text = buffer.readVersionedString()
            9 -> displayDuration = buffer.readUnsignedShort()
            10 -> field10 = buffer.readShort()
            11 -> field14 = 0
            12 -> replacementValue = buffer.readUnsignedByte()
            13 -> field13 = buffer.readShort()
            14 -> field14 = buffer.readUnsignedShort()
            15 -> {}
            16 -> {
                field16a = buffer.readShort()
                field16b = buffer.readShort()
            }
            19 -> field19 = buffer.readShort()
            20 -> field20 = buffer.readShort()
            17, 18 -> readShortTransforms(buffer, opcode == 18, wideVarbit = false)
            21, 22 -> readShortTransforms(buffer, opcode == 22, wideVarbit = true)
            else -> unknown(opcode, buffer)
        }
    }
}
