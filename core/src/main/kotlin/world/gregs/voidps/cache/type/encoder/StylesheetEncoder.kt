package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.StylesheetType

class StylesheetEncoder : TypeEncoder<StylesheetType> {

    override fun Writer.encode(definition: StylesheetType) {
        writeShort(definition.parent)
        writeShort(definition.properties.size)
        for (property in definition.properties) {
            writeByte(property.kind)
            writeInt(property.nameHash)
            writeInt(property.value)
        }
    }
}
