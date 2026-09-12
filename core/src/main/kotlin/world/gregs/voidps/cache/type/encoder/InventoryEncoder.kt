package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.InvType

class InventoryEncoder : OpcodeEncoder<InvType>() {

    override fun opcodes(definition: InvType): IntArray {
        val opcodes = IntArrayList()
        if (definition.length != 0) {
            opcodes.add(2)
        }
        if (definition.ids != null) {
            opcodes.add(21)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: InvType, opcode: Int, occurrence: Int) {
        val ids = definition.ids ?: IntArray(0)
        val amounts = definition.amounts ?: IntArray(0)
        when (opcode) {
            2 -> writeShort(definition.length)
            4 -> {
                writeByte(ids.size)
                for (index in ids.indices) {
                    writeShort(ids[index])
                    writeShort(amounts[index])
                }
            }
            21 -> {
                writeByte(ids.size)
                for (index in ids.indices) {
                    writeMedium(ids[index])
                    writeShort(amounts[index])
                }
            }
            else -> error("Unhandled inv opcode $opcode in ${definition.id}")
        }
    }
}
