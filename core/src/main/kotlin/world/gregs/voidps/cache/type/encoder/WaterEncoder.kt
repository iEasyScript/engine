package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.WaterType

class WaterEncoder : OpcodeEncoder<WaterType>() {

    override fun opcodes(definition: WaterType): IntArray {
        val opcodes = IntArrayList()
        opcodes.addUnused(definition)
        return opcodes.toIntArray().also { it.sort() }
    }

    override fun Writer.encodeOpcode(definition: WaterType, opcode: Int, occurrence: Int) {
        writeUnused(definition, opcode, occurrence)
    }
}
