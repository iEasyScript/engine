package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.VarBitType

class VarBitEncoder : OpcodeEncoder<VarBitType>() {

    override fun opcodes(definition: VarBitType): IntArray {
        val opcodes = IntArrayList()
        opcodes.add(1)
        opcodes.add(2)
        if (definition.flags != 0) {
            opcodes.add(16)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: VarBitType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                writeByte(definition.domainId.toInt())
                writeBigSmart(definition.index)
            }
            2 -> {
                writeByte(definition.startBit)
                writeByte(definition.endBit)
            }
            16 -> Unit
            else -> error("Unhandled varbit opcode $opcode in ${definition.id}")
        }
    }
}
