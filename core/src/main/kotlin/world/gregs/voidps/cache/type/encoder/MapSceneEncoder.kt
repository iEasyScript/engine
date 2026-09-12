package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.MapSceneType

class MapSceneEncoder : OpcodeEncoder<MapSceneType>() {

    override fun opcodes(definition: MapSceneType): IntArray {
        val opcodes = IntArrayList()
        if (definition.graphic != 0) {
            opcodes.add(1)
        }
        if (definition.colour != 0) {
            opcodes.add(2)
        }
        if (definition.unknown3) {
            opcodes.add(3)
        }
        if (definition.unknown5) {
            opcodes.add(5)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: MapSceneType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.graphic)
            2 -> writeMedium(definition.colour)
            3, 4, 5 -> Unit
            else -> error("Unhandled mapscene opcode $opcode in ${definition.id}")
        }
    }
}
