package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.WorldAreaCoord
import world.gregs.voidps.cache.type.data.WorldAreaType

/** Writes an index 2 archive 83 world area file, whose rectangles and points are one record each. */
class WorldAreaEncoder : OpcodeEncoder<WorldAreaType>() {

    override fun opcodes(definition: WorldAreaType): IntArray {
        val opcodes = IntArrayList()
        if (definition.value != -1) {
            opcodes.add(2)
        }
        repeat(definition.rects?.size ?: 0) { opcodes.add(3) }
        repeat(definition.points?.size ?: 0) { opcodes.add(4) }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: WorldAreaType, opcode: Int, occurrence: Int) {
        when (opcode) {
            2 -> writeMedium(definition.value)
            3 -> {
                val rect = definition.rects!![occurrence]
                writeInt(pack(rect.from))
                writeInt(pack(rect.to))
            }
            4 -> {
                val point = definition.points!![occurrence]
                writeInt(pack(point.coord))
                writeInt(point.value)
            }
            else -> error("Unhandled worldarea opcode $opcode in ${definition.id}")
        }
    }

    private fun pack(coord: WorldAreaCoord): Int =
        if (coord.plane < 0) -1 else (coord.plane shl 28) or (coord.x shl 14) or coord.y
}
