package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.SeqGroupType

class SeqGroupEncoder : OpcodeEncoder<SeqGroupType>() {

    override fun opcodes(definition: SeqGroupType): IntArray {
        val opcodes = IntArrayList()
        if (definition.field1 != 0) {
            opcodes.add(1)
        }
        if (definition.transitions != null) {
            opcodes.add(2)
        }
        if (definition.field3 != 0) {
            opcodes.add(3)
        }
        if (definition.field4 != 0) {
            opcodes.add(4)
        }
        if (definition.field5 != -1) {
            opcodes.add(5)
        }
        if (definition.field6 != -1) {
            opcodes.add(6)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: SeqGroupType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeShort(definition.field1)
            2 -> {
                val transitions = definition.transitions ?: IntArray(0)
                writeByte(transitions.size)
                for (transition in transitions) {
                    writeShort(transition)
                }
            }
            3 -> writeByte(definition.field3)
            4 -> writeByte(definition.field4)
            5 -> writeBigSmart(definition.field5)
            6 -> writeBigSmart(definition.field6)
            else -> error("Unhandled seqgroup opcode $opcode in ${definition.id}")
        }
    }
}
