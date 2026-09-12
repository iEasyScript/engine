package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.SpotAnimType
import kotlin.math.PI
import kotlin.math.roundToInt

class SpotAnimEncoder : OpcodeEncoder<SpotAnimType>() {

    override fun opcodes(definition: SpotAnimType): IntArray {
        val opcodes = IntArrayList()
        if (definition.modelId != 0) {
            opcodes.add(1)
        }
        if (definition.animationId != -1) {
            opcodes.add(2)
        }
        if (definition.scaleX != 1.0f) {
            opcodes.add(4)
        }
        if (definition.scaleY != 1.0f) {
            opcodes.add(5)
        }
        if (definition.rotation != 0.0f) {
            opcodes.add(6)
        }
        if (definition.ambience != 0) {
            opcodes.add(7)
        }
        if (definition.unknown8 != 0) {
            opcodes.add(8)
        }
        if (definition.unknown10) {
            opcodes.add(10)
        }
        if (definition.originalColours != null) {
            opcodes.add(40)
        }
        if (definition.originalTextureColours != null) {
            opcodes.add(41)
        }
        if (definition.unknown44 != 0) {
            opcodes.add(44)
        }
        if (definition.unknown45 != 0) {
            opcodes.add(45)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: SpotAnimType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.modelId)
            2 -> writeBigSmart(definition.animationId)
            4 -> writeShort((definition.scaleX * SCALE).roundToInt())
            5 -> writeShort((definition.scaleY * SCALE).roundToInt())
            6 -> writeShort((definition.rotation.toDouble() * 180.0 / PI).roundToInt())
            7 -> writeByte(definition.ambience - AMBIENCE_BIAS)
            8 -> writeByte(definition.unknown8)
            10, 46 -> Unit
            40 -> writeColours(definition)
            41 -> writeTextures(definition)
            44 -> writeShort(definition.unknown44)
            45 -> writeShort(definition.unknown45)
            else -> error("Unhandled spotanim opcode $opcode in ${definition.id}")
        }
    }

    private companion object {
        const val SCALE = 128.0f
        const val AMBIENCE_BIAS = 64
    }
}
