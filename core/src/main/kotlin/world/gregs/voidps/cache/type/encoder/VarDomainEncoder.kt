package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.VarDomainType

/** Writes any of the eleven var domain archives, whose files all share one opcode table. */
class VarDomainEncoder<T : VarDomainType> : OpcodeEncoder<T>() {

    override fun opcodes(definition: T): IntArray {
        val opcodes = IntArrayList()
        if (definition.type != -1) {
            opcodes.add(3)
        }
        if (definition.lifetime != VarDomainType.TEMPORARY) {
            opcodes.add(4)
        }
        if (definition.transmit != 0) {
            opcodes.add(5)
        }
        opcodes.addUnused(definition)
        return opcodes.toIntArray().also { it.sort() }
    }

    override fun Writer.encodeOpcode(definition: T, opcode: Int, occurrence: Int) {
        when (opcode) {
            3 -> writeByte(definition.type)
            4 -> writeByte(definition.lifetime)
            5 -> writeByte(definition.transmit)
            7, 8 -> Unit
            else -> writeUnused(definition, opcode, occurrence)
        }
    }
}
