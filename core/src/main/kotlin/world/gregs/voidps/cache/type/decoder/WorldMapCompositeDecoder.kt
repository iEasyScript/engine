package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.WORLD_MAP
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.WorldMapCompositeOverlay
import world.gregs.voidps.cache.type.data.WorldMapCompositeType

/**
 * Decodes the overlay table of a map area's composite file, which is everything after the image.
 *
 * A composite file is an int length, that many bytes of PNG and then the table; [image] and
 * [overlays] are the split, and the length is the image's own size so nothing has to store it.
 */
class WorldMapCompositeDecoder : TypeDecoder<WorldMapCompositeType>(WORLD_MAP) {

    override fun getArchive(id: Int) = COMPOSITE_ARCHIVE

    override fun size(cache: Cache) = cache.lastFileId(index, COMPOSITE_ARCHIVE)

    override fun create(size: Int) = Array(size) { WorldMapCompositeType(it) }

    override fun load(definitions: Array<WorldMapCompositeType>, cache: Cache, id: Int) {
        val data = cache.data(index, getArchive(id), getFile(id)) ?: return
        read(definitions, id, BufferReader(overlays(data)))
    }

    override fun readLoop(definition: WorldMapCompositeType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun WorldMapCompositeType.read(opcode: Int, buffer: Reader) = Unit

    private fun WorldMapCompositeType.decode(buffer: Reader) {
        val entries = ArrayList<WorldMapCompositeOverlay>(buffer.readableBytes() / OVERLAY_BYTES)
        while (buffer.readableBytes() >= OVERLAY_BYTES) {
            entries.add(
                WorldMapCompositeOverlay(
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readUnsignedShort(),
                )
            )
        }
        overlays = entries
    }

    companion object {
        const val COMPOSITE_ARCHIVE = 4
        const val OVERLAY_BYTES = 14

        private const val LENGTH_BYTES = 4

        private fun imageLength(file: ByteArray): Int =
            ((file[0].toInt() and 0xff) shl 24) or ((file[1].toInt() and 0xff) shl 16) or
                ((file[2].toInt() and 0xff) shl 8) or (file[3].toInt() and 0xff)

        /** The composite image, whose size is the length the file leads with. */
        fun image(file: ByteArray): ByteArray = file.copyOfRange(LENGTH_BYTES, LENGTH_BYTES + imageLength(file))

        /** The overlay table that follows it, empty for the areas that carry none. */
        fun overlays(file: ByteArray): ByteArray = file.copyOfRange(LENGTH_BYTES + imageLength(file), file.size)
    }
}
