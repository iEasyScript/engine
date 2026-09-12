package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.WATER
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.WaterType
import world.gregs.voidps.cache.type.encoder.WaterEncoder

class WaterDecoder : ConfigDecoder<WaterType>(WATER) {

    override fun create(size: Int) = Array(size) { WaterType(it) }

    private val encoder = WaterEncoder()

    override fun canonicalOpcodes(definition: WaterType): IntArray = encoder.opcodes(definition)

    override fun WaterType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            2 -> keep(opcode, buffer.capture {
                val count = readSmart()
                repeat(count) { readSmart() }
                overlayFlags = count != 0
            })
            3 -> keep(opcode, buffer.capture {
                skip(1)
                val count = readSmart()
                repeat(count) {
                    readSmart()
                    skip(1)
                }
                underlayFlags = count != 0
            })
            4 -> keep(opcode, buffer.capture {
                heightScale = readUnsignedByte()
                val count = readSmart()
                repeat(count) {
                    readSmart()
                    skip(1)
                }
                heightsFlag = count != 0
            })
            else -> unknown(opcode, buffer)
        }
    }
}
