package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.WorldMapCoordsType

class WorldMapCoordsEncoder : TypeEncoder<WorldMapCoordsType> {

    override fun Writer.encode(definition: WorldMapCoordsType) {
        writeShort(definition.squares.size)
        for (link in definition.squares) {
            writeByte(link.level)
            writeByte(link.unknown2)
            writeShort(link.sourceSquareX)
            writeShort(link.sourceSquareY)
            writeByte(link.unknown5)
            writeShort(link.mapSquareX)
            writeShort(link.mapSquareY)
        }
        writeShort(definition.zones.size)
        for (link in definition.zones) {
            writeByte(link.level)
            writeByte(link.unknown2)
            writeShort(link.sourceSquareX)
            writeShort(link.sourceSquareY)
            writeByte(link.sourceZoneX)
            writeByte(link.sourceZoneY)
            writeByte(link.unknown7)
            writeShort(link.mapSquareX)
            writeShort(link.mapSquareY)
            writeByte(link.mapZoneX)
            writeByte(link.mapZoneY)
        }
        writeByte(definition.width)
        writeByte(definition.height)
    }
}
