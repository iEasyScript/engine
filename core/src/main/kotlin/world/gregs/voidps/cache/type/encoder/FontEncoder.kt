package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.FontType

class FontEncoder : TypeEncoder<FontType> {

    override fun Writer.encode(definition: FontType) {
        writeByte(definition.unknown1)
        writeByte(definition.unknown2)
        writeBytes(definition.glyphWidths)
        writeBytes(definition.glyphHeights)
        writeBytes(definition.glyphTopOffsets)
        writeShort(definition.atlasWidth)
        writeShort(definition.atlasHeight)
        for (x in definition.glyphAtlasX) {
            writeShort(x)
        }
        for (y in definition.glyphAtlasY) {
            writeShort(y)
        }
        writeByte(definition.unknown3)
        writeByte(definition.unknown4)
        writeByte(definition.unknown5)
        writeByte(definition.unknown6)
        writeByte(definition.unknown7)
        writeByte(definition.unknown8)
    }
}
