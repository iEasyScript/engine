package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.CursorType

class CursorEncoder : OpcodeEncoder<CursorType>() {

    override fun opcodes(definition: CursorType): IntArray {
        val opcodes = IntArrayList()
        if (definition.graphicId != -1) {
            opcodes.add(1)
        }
        if (definition.hotspotX != 0 || definition.hotspotY != 0) {
            opcodes.add(2)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: CursorType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.graphicId)
            2 -> {
                writeByte(definition.hotspotX)
                writeByte(definition.hotspotY)
            }
            else -> error("Unhandled cursor opcode $opcode in ${definition.id}")
        }
    }
}
