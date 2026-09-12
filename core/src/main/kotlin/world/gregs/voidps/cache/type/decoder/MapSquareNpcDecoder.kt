package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.type.data.MapSquareNpcSpawn

/** The lossless read of an npc spawn file: a packed position and an npc id per spawn, to the end. */
object MapSquareNpcDecoder {

    fun decode(data: ByteArray): MutableList<MapSquareNpcSpawn> {
        val buffer = BufferReader(data)
        val spawns = ArrayList<MapSquareNpcSpawn>(data.size / 4)
        while (buffer.remaining > 0) {
            val position = buffer.readUnsignedShort()
            spawns.add(
                MapSquareNpcSpawn(
                    id = buffer.readUnsignedShort(),
                    localX = (position shr 7) and 0x7f,
                    localY = position and 0x7f,
                    level = position shr 14
                )
            )
        }
        return spawns
    }
}
