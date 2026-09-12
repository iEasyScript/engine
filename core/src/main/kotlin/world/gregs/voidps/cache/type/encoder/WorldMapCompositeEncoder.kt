package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.WorldMapCompositeType

class WorldMapCompositeEncoder : TypeEncoder<WorldMapCompositeType> {

    override fun Writer.encode(definition: WorldMapCompositeType) {
        for (overlay in definition.overlays) {
            writeInt(overlay.unknown1)
            writeInt(overlay.colour)
            writeInt(overlay.secondColour)
            writeShort(overlay.unknown4)
        }
    }
}
