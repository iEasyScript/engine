package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.FontMetricsType
import world.gregs.voidps.cache.type.data.FontMetricsType.Companion.BITMAP
import world.gregs.voidps.cache.type.data.FontMetricsType.Companion.SCALABLE

class FontMetricsEncoder : TypeEncoder<FontMetricsType> {

    override fun Writer.encode(definition: FontMetricsType) {
        writeByte(definition.version)
        if (definition.version == SCALABLE) {
            writeInt(definition.fontFileId!!)
            writeByte(definition.pointSize!!)
            return
        }
        writeByte(definition.flags!!)
        if (definition.version == BITMAP) {
            writeInt(definition.graphicGroupId!!)
        }
        for (value in definition.cellWidth!!) writeByte(value)
        for (value in definition.cellHeight!!) writeByte(value)
        for (value in definition.topBearing!!) writeByte(value)
        writeShort(definition.atlasWidth!!)
        writeShort(definition.atlasHeight!!)
        for (value in definition.atlasX!!) writeShort(value)
        for (value in definition.atlasY!!) writeShort(value)
        val trailer = definition.variableBlock
        if (trailer != null) {
            writeBytes(trailer)
            return
        }
        writeByte(definition.baseline!!)
        for (value in definition.pad!!) writeByte(value)
        writeByte(definition.downscale!!)
    }
}
