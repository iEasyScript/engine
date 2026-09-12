package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.SPOTANIMS
import world.gregs.voidps.cache.type.data.SpotAnimType
import world.gregs.voidps.cache.type.encoder.SpotAnimEncoder
import kotlin.math.PI

class SpotAnimDecoder : TypeDecoder<SpotAnimType>(SPOTANIMS) {
    override fun create(size: Int) = Array(size) { SpotAnimType(it) }

    private val encoder = SpotAnimEncoder()

    override fun canonicalOpcodes(definition: SpotAnimType): IntArray = encoder.opcodes(definition)

    override fun getFile(id: Int) = id and 0xff

    override fun getArchive(id: Int) = id ushr 8

    override fun SpotAnimType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> modelId = buffer.readBigSmart()
            2 -> animationId = buffer.readBigSmart()
            4 -> scaleX = buffer.readUnsignedShort() / 128.0f
            5 -> scaleY = buffer.readUnsignedShort() / 128.0f
            6 -> rotation = (buffer.readUnsignedShort() * PI / 180.0).toFloat()
            7 -> ambience = buffer.readByte() + 64
            8 -> unknown8 = buffer.readUnsignedByte()
            9 -> {
                unknown9a = 3
                unknown9b = 8224
            }
            10 -> unknown10 = true
            15 -> {
                unknown9a = 3
                unknown9b = buffer.readUnsignedShort()
            }
            16 -> {
                unknown9a = 3
                unknown9b = buffer.readInt()
            }
            40 -> readColours(buffer)
            41 -> readTextures(buffer)
            44 -> unknown44 = buffer.readUnsignedShort()
            45 -> unknown45 = buffer.readUnsignedShort()
            46 -> { }
            else -> unknown(opcode, buffer)
        }
    }
}