package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.FONT_METRICS
import world.gregs.voidps.cache.type.data.FontType

class FontDecoder : TypeDecoder<FontType>(FONT_METRICS) {
    override fun size(cache: Cache) = cache.lastArchiveId(index)

    override fun create(size: Int) = Array(size) { FontType(it) }

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: FontType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun FontType.read(opcode: Int, buffer: Reader) = Unit

    private fun FontType.decode(buffer: Reader) {
        unknown1 = buffer.readUnsignedByte()
        unknown2 = buffer.readUnsignedByte()
        glyphWidths = buffer.readGlyphBytes()
        glyphHeights = buffer.readGlyphBytes()
        glyphTopOffsets = buffer.readGlyphBytes()
        atlasWidth = buffer.readUnsignedShort()
        atlasHeight = buffer.readUnsignedShort()
        glyphAtlasX = IntArray(GLYPHS) { buffer.readUnsignedShort() }
        glyphAtlasY = IntArray(GLYPHS) { buffer.readUnsignedShort() }
        unknown3 = buffer.readUnsignedByte()
        unknown4 = buffer.readUnsignedByte()
        unknown5 = buffer.readUnsignedByte()
        unknown6 = buffer.readUnsignedByte()
        unknown7 = buffer.readUnsignedByte()
        unknown8 = buffer.readUnsignedByte()
    }

    private fun Reader.readGlyphBytes() = ByteArray(GLYPHS).also { readBytes(it) }

    private companion object {
        const val GLYPHS = 256
    }
}
