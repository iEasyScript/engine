package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.WorldMapAreaElementsType

class WorldMapAreaElementsEncoder : TypeEncoder<WorldMapAreaElementsType> {

    override fun Writer.encode(definition: WorldMapAreaElementsType) {
        writeShort(definition.elements.size)
        for (element in definition.elements) {
            writeInt(element.coord)
            writeShort(element.mapElementId)
            writeByte(element.flag)
        }
    }
}
