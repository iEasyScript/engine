package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.UnderlayType

class UnderlayEncoder : OpcodeEncoder<UnderlayType>() {

    override fun opcodes(definition: UnderlayType): IntArray {
        val opcodes = IntArrayList()
        if (definition.colour != 0) {
            opcodes.add(1)
        }
        if (definition.texture != -1) {
            opcodes.add(2)
        }
        if (definition.scale != 512) {
            opcodes.add(3)
        }
        if (!definition.blockShadow) {
            opcodes.add(4)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: UnderlayType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeMedium(definition.colour)
            2 -> writeShort(if (definition.texture == -1) 65535 else definition.texture)
            3 -> writeShort(definition.scale ushr 2)
            4, 5 -> Unit
            else -> error("Unhandled underlay opcode $opcode in ${definition.id}")
        }
    }
}
