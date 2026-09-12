package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.type.data.MapSquareNpcSpawn

/** The inverse of `MapSquareNpcDecoder`. */
object MapSquareNpcEncoder {

    fun Writer.encode(spawns: List<MapSquareNpcSpawn>) {
        for (spawn in spawns) {
            writeShort((spawn.level shl 14) or (spawn.localX shl 7) or spawn.localY)
            writeShort(spawn.id)
        }
    }
}
