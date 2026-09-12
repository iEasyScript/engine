package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.BillboardType

class BillboardEncoder : OpcodeEncoder<BillboardType>() {

    override fun opcodes(definition: BillboardType): IntArray {
        val opcodes = IntArrayList()
        if (definition.size2d != null || definition.size3d != null) {
            opcodes.add(2)
        }
        if (definition.unknown3 != null) {
            opcodes.add(3)
        }
        if (definition.unknown4 != null) {
            opcodes.add(4)
        }
        if (definition.unknown5 != null) {
            opcodes.add(5)
        }
        if (definition.unknown7) {
            opcodes.add(7)
        }
        if (definition.material != null) {
            opcodes.add(1)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: BillboardType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeShort(definition.material ?: 0)
            2 -> {
                writeShort(definition.size2d ?: 0)
                writeShort(definition.size3d ?: 0)
            }
            3 -> writeByte(definition.unknown3 ?: 0)
            4 -> writeByte(definition.unknown4 ?: 0)
            5 -> writeByte(definition.unknown5 ?: 0)
            7 -> Unit
            else -> error("Unhandled billboard opcode $opcode in ${definition.id}")
        }
    }
}
