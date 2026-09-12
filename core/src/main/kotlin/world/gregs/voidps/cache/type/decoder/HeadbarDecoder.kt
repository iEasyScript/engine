package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.HIT_BARS
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.HeadbarType
import world.gregs.voidps.cache.type.encoder.HeadbarEncoder

class HeadbarDecoder : ConfigDecoder<HeadbarType>(HIT_BARS) {

    override fun create(size: Int) = Array(size) { HeadbarType(it) }

    private val encoder = HeadbarEncoder()

    override fun canonicalOpcodes(definition: HeadbarType): IntArray = encoder.opcodes(definition)

    override fun HeadbarType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1, 6 -> buffer.skip(2)
            2 -> sortKey = buffer.readUnsignedByte()
            3 -> field3 = buffer.readUnsignedByte()
            4 -> fadeStart = 0
            5 -> fadeEnd = buffer.readUnsignedShort()
            7 -> sizeClass0Fill = buffer.readBigSmart()
            8 -> sizeClass0Frame = buffer.readBigSmart()
            9 -> sizeClass1Fill = buffer.readBigSmart()
            10 -> sizeClass1Frame = buffer.readBigSmart()
            11 -> fadeStartDelay = buffer.readUnsignedShort()
            12 -> sizeClass2Fill = buffer.readBigSmart()
            13 -> sizeClass2Frame = buffer.readBigSmart()
            14 -> centredOverlay = buffer.readBigSmart()
            15 -> fillEdgeCap = buffer.readBigSmart()
            16 -> useAlternateBlitter = true
            17 -> barHeightPx = buffer.readUnsignedByte()
            else -> unknown(opcode, buffer)
        }
    }
}
