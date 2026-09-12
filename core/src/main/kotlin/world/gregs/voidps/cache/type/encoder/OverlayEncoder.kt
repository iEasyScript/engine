package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.OverlayType

/**
 * Writes an index 2 archive 4 overlay file.
 *
 * The colours are stored twice: the packed hsl the client derives is not invertible, so the file's own
 * rgb rides alongside it and is what goes back out.
 */
class OverlayEncoder : OpcodeEncoder<OverlayType>() {

    override fun opcodes(definition: OverlayType): IntArray {
        val opcodes = IntArrayList()
        if (definition.colourRgb != -1) {
            opcodes.add(1)
        }
        if (definition.texture != -1) {
            opcodes.add(3)
        }
        if (definition.blendColourRgb != -1) {
            opcodes.add(7)
        }
        if (definition.scale != 512) {
            opcodes.add(9)
        }
        if (!definition.blockShadow) {
            opcodes.add(10)
        }
        if (definition.unknown11 != 8) {
            opcodes.add(11)
        }
        if (definition.underlayOverrides) {
            opcodes.add(12)
        }
        if (definition.waterColour != 0) {
            opcodes.add(13)
        }
        if (definition.waterScale != 64) {
            opcodes.add(14)
        }
        if (definition.waterIntensity != 255) {
            opcodes.add(16)
        }
        if (definition.unknown20 != 63) {
            opcodes.add(20)
        }
        if (definition.unknown21) {
            opcodes.add(21)
        }
        if (definition.unknown22 != 64) {
            opcodes.add(22)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: OverlayType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeMedium(definition.colourRgb)
            2 -> writeByte(definition.texture)
            3 -> writeShort(if (definition.texture == -1) 65535 else definition.texture)
            5, 8 -> Unit
            7 -> writeMedium(definition.blendColourRgb)
            9 -> writeShort(definition.scale ushr 2)
            10 -> Unit
            11 -> writeByte(definition.unknown11)
            12 -> Unit
            13 -> writeMedium(definition.waterColour)
            14 -> writeByte(definition.waterScale ushr 2)
            16 -> writeByte(definition.waterIntensity)
            20 -> writeShort(definition.unknown20)
            21 -> writeByte(if (definition.unknown21) 1 else 0)
            22 -> writeShort(definition.unknown22)
            else -> error("Unhandled overlay opcode $opcode in ${definition.id}")
        }
    }
}
