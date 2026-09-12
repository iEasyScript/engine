package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class WorldAreaCoord(val plane: Int, val x: Int, val y: Int) {
    companion object {
        val NULL = WorldAreaCoord(-1, 0, 0)

        fun decode(packed: Int): WorldAreaCoord {
            if (packed == -1) {
                return NULL
            }
            val plane = (packed ushr 28) and 0x3
            val x = (packed ushr 14) and 0x3FFF
            val y = packed and 0x3FFF
            return WorldAreaCoord(plane, x, y)
        }
    }
}

data class WorldAreaRect(val from: WorldAreaCoord, val to: WorldAreaCoord)

data class WorldAreaPoint(val coord: WorldAreaCoord, val value: Int)

data class WorldAreaType(
    override var id: Int = -1,
    var value: Int = -1,
    var rects: MutableList<WorldAreaRect>? = null,
    var points: MutableList<WorldAreaPoint>? = null,
) : CacheType, OpcodeOrdered {
    override fun repeats(opcode: Int): Boolean = opcode == RECT || opcode == POINT

    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    companion object {
        private const val RECT = 3
        private const val POINT = 4

        val EMPTY = WorldAreaType()
    }
}
