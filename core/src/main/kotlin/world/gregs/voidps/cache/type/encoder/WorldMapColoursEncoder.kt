package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.WorldMapColoursType

class WorldMapColoursEncoder : TypeEncoder<WorldMapColoursType> {

    override fun Writer.encode(definition: WorldMapColoursType) {
        val last = definition.runs.size - 1
        for (index in definition.runs.indices) {
            val run = definition.runs[index]
            writeMedium(run.colour)
            if (index != last) {
                writeByte(run.length)
            }
        }
    }
}
