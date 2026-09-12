package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.ParamType

class ParamEncoder : OpcodeEncoder<ParamType>() {

    override fun opcodes(definition: ParamType): IntArray {
        val opcodes = IntArrayList()
        if (definition.type.code != 0) {
            opcodes.add(1)
        }
        if (definition.defaultInt != 0) {
            opcodes.add(2)
        }
        if (!definition.autoDisable) {
            opcodes.add(4)
        }
        if (definition.defaultString != null) {
            opcodes.add(5)
        }
        if (definition.typeId != 0) {
            opcodes.add(101)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: ParamType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeTypeChar(definition.type)
            2 -> writeInt(definition.defaultInt)
            4 -> Unit
            5 -> writeString(definition.defaultString)
            101 -> writeSmart(definition.typeId)
            else -> error("Unhandled param opcode $opcode in ${definition.id}")
        }
    }
}
