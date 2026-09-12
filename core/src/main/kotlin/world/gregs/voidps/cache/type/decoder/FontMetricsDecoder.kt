package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.FontMetricsType
import world.gregs.voidps.cache.type.data.FontMetricsType.Companion.BITMAP
import world.gregs.voidps.cache.type.data.FontMetricsType.Companion.CHARACTERS
import world.gregs.voidps.cache.type.data.FontMetricsType.Companion.PADDING
import world.gregs.voidps.cache.type.data.FontMetricsType.Companion.SCALABLE
import world.gregs.voidps.cache.type.data.FontMetricsType.Companion.VARIABLE_TRAILER

/** A group of the font metrics index is one record, so a group id is a definition id. */
class FontMetricsDecoder : TypeDecoder<FontMetricsType>(INDEX) {

    override fun create(size: Int) = Array(size) { FontMetricsType(it) }

    override fun size(cache: Cache) = cache.lastArchiveId(index)

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: FontMetricsType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun FontMetricsType.read(opcode: Int, buffer: Reader) = Unit

    private fun FontMetricsType.decode(buffer: Reader) {
        version = buffer.readUnsignedByte()
        require(version <= SCALABLE) { "Font metrics $id has unknown version $version." }
        if (version == SCALABLE) {
            fontFileId = buffer.readInt()
            pointSize = buffer.readUnsignedByte()
            return
        }
        val flags = buffer.readUnsignedByte()
        this.flags = flags
        if (version == BITMAP) {
            graphicGroupId = buffer.readInt()
        }
        cellWidth = IntArray(CHARACTERS) { buffer.readUnsignedByte() }
        cellHeight = IntArray(CHARACTERS) { buffer.readUnsignedByte() }
        topBearing = IntArray(CHARACTERS) { buffer.readByte() }
        atlasWidth = buffer.readUnsignedShort()
        atlasHeight = buffer.readUnsignedShort()
        atlasX = IntArray(CHARACTERS) { buffer.readUnsignedShort() }
        atlasY = IntArray(CHARACTERS) { buffer.readUnsignedShort() }
        if (flags and VARIABLE_TRAILER != 0) {
            variableBlock = ByteArray(buffer.readableBytes()).also { buffer.readBytes(it) }
            return
        }
        baseline = buffer.readUnsignedByte()
        pad = IntArray(PADDING) { buffer.readUnsignedByte() }
        downscale = buffer.readUnsignedByte()
    }

    companion object {
        const val INDEX = 58
    }
}
