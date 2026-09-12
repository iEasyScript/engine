package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.WorldMapType

class WorldMapDetailsEncoder : TypeEncoder<WorldMapType> {

    override fun Writer.encode(definition: WorldMapType) {
        writeString(definition.map)
        writeString(definition.name)
        writeInt(definition.position)
        writeInt(definition.colour)
        writeByte(definition.static)
        writeByte(definition.unknown6)
        writeByte(definition.unknown7)
        writeByte(definition.sections.size)
        for (section in definition.sections) {
            writeByte(section.level)
            writeShort(section.minX)
            writeShort(section.minY)
            writeShort(section.maxX)
            writeShort(section.maxY)
            writeShort(section.startX)
            writeShort(section.startY)
            writeShort(section.endX)
            writeShort(section.endY)
        }
    }
}
