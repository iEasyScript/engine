package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.RENDER_ANIMATIONS
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.BASType
import world.gregs.voidps.cache.type.encoder.BASEncoder

class BASDecoder : ConfigDecoder<BASType>(RENDER_ANIMATIONS) {
    override fun create(size: Int) = Array(size) { BASType(it) }

    private val encoder = BASEncoder()

    override fun canonicalOpcodes(definition: BASType): IntArray = encoder.opcodes(definition)

    override fun BASType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> {
                standAnim = buffer.readBigSmart()
                standTurnAnim = buffer.readBigSmart()
            }
            2 -> walkAnim = buffer.readBigSmart()
            3 -> runAnim = buffer.readBigSmart()
            4 -> turnAroundAnim = buffer.readBigSmart()
            5 -> turnRightAnim = buffer.readBigSmart()
            6 -> walkBackAnim = buffer.readBigSmart()
            7 -> walkLeftAnim = buffer.readBigSmart()
            8 -> walkRightAnim = buffer.readBigSmart()
            9 -> crawlAnim = buffer.readBigSmart()
            26 -> {
                renderOffsetX = buffer.readUnsignedByte() shl 2
                renderOffsetY = buffer.readUnsignedByte() shl 2
            }
            27 -> keep(opcode, buffer.capture { skip(readUnsignedByte() * 2) })
            28 -> keep(opcode, buffer.capture { skip(readUnsignedByte()) })
            29, 31, 34, 37 -> keep(opcode, buffer.capture { skip(1) })
            30, 32, 33, 35, 36 -> keep(opcode, buffer.capture { skip(2) })
            38 -> anim38 = buffer.readBigSmart()
            39 -> anim39 = buffer.readBigSmart()
            40 -> anim40 = buffer.readBigSmart()
            41 -> anim41 = buffer.readBigSmart()
            42 -> anim42 = buffer.readBigSmart()
            43 -> anim43 = buffer.readBigSmart()
            44 -> anim44 = buffer.readBigSmart()
            45 -> field45 = buffer.readUnsignedShort()
            46 -> anim46 = buffer.readBigSmart()
            47 -> anim47 = buffer.readBigSmart()
            48 -> anim48 = buffer.readBigSmart()
            49 -> anim49 = buffer.readBigSmart()
            50 -> anim50 = buffer.readBigSmart()
            51 -> anim51 = buffer.readBigSmart()
            52 -> keep(opcode, buffer.capture {
                repeat(readUnsignedByte()) {
                    readBigSmart()
                    readUnsignedByte()
                    skip(readUnsignedByte())
                }
            })
            53 -> { }
            54 -> {
                renderOffsetX2 = buffer.readUnsignedByte() shl 2
                renderOffsetY2 = buffer.readUnsignedByte() shl 2
            }
            55 -> keep(opcode, buffer.capture { skip(3) })
            56 -> keep(opcode, buffer.capture { skip(7) })
            else -> unknown(opcode, buffer)
        }
    }
}
