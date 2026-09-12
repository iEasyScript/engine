package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.GRAPHICS
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.OPAQUE
import world.gregs.voidps.cache.type.data.PalettedGraphic
import world.gregs.voidps.cache.type.data.RawGraphic
import world.gregs.voidps.cache.type.data.GraphicFrame
import world.gregs.voidps.cache.type.data.GraphicType

private const val TRAILER_BYTES = 2
private const val RAW_LAYOUT = 0x8000
private const val FRAME_COUNT_MASK = 0x7fff

private const val CANVAS_BYTES = 5
private const val FRAME_HEADER_BYTES = 8
private const val COLOUR_BYTES = 3
private const val COLUMN_MAJOR = 0x1
private const val ALPHA_PLANE = 0x2

private const val RAW_ALPHA_PRESENT = 1

class GraphicDecoder : TypeDecoder<GraphicType>(GRAPHICS) {

    override fun size(cache: Cache) = cache.lastArchiveId(index)

    override fun create(size: Int) = Array(size) { GraphicType(it) }

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: GraphicType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun GraphicType.read(opcode: Int, buffer: Reader) = Unit

    private fun GraphicType.decode(buffer: Reader) {
        val length = buffer.array().size
        check(length >= TRAILER_BYTES) { "Graphic $id has $length bytes, too few for a layout trailer" }
        buffer.position(length - TRAILER_BYTES)
        val trailer = buffer.readUnsignedShort()
        val frameCount = trailer and FRAME_COUNT_MASK
        if (trailer and RAW_LAYOUT != 0) {
            decodeRaw(buffer, frameCount)
        } else {
            decodePaletted(buffer, frameCount, length)
        }
        buffer.position(length)
    }

    private fun GraphicType.decodeRaw(buffer: Reader, frameCount: Int) {
        buffer.position(0)
        if (buffer.readUnsignedByte() != 0) {
            frames = emptyArray()
            return
        }
        val alphaPresent = buffer.readUnsignedByte() == RAW_ALPHA_PRESENT
        val width = buffer.readUnsignedShort()
        val height = buffer.readUnsignedShort()
        maxWidth = width
        maxHeight = height
        val pixels = width * height
        frames = Array(frameCount) {
            val rgb = ByteArray(pixels * COLOUR_BYTES)
            buffer.readBytes(rgb)
            val alpha = if (alphaPresent) ByteArray(pixels).also(buffer::readBytes) else null
            RawGraphic(width, height, rgb, alpha)
        }
    }

    private fun GraphicType.decodePaletted(buffer: Reader, frameCount: Int, length: Int) {
        val headerStart = length - TRAILER_BYTES - CANVAS_BYTES - FRAME_HEADER_BYTES * frameCount
        check(headerStart >= 0) { "Graphic $id has $length bytes, too few for $frameCount frame headers" }
        buffer.position(headerStart)
        maxWidth = buffer.readUnsignedShort()
        maxHeight = buffer.readUnsignedShort()
        val paletteSize = buffer.readUnsignedByte() + 1
        val offsetsX = IntArray(frameCount) { buffer.readUnsignedShort() }
        val offsetsY = IntArray(frameCount) { buffer.readUnsignedShort() }
        val widths = IntArray(frameCount) { buffer.readUnsignedShort() }
        val heights = IntArray(frameCount) { buffer.readUnsignedShort() }

        val paletteStart = headerStart - (paletteSize - 1) * COLOUR_BYTES
        buffer.position(paletteStart)
        val raw = IntArray(paletteSize)
        val palette = IntArray(paletteSize)
        for (colour in 1 until paletteSize) {
            raw[colour] = buffer.readUnsignedMedium()
            palette[colour] = raw[colour].coerceAtLeast(1)
        }
        rawPalette = raw

        buffer.position(0)
        frames = Array<GraphicFrame>(frameCount) { frame ->
            val width = widths[frame]
            val height = heights[frame]
            val flags = buffer.readUnsignedByte()
            val columnMajor = flags and COLUMN_MAJOR != 0
            val indices = buffer.readPlane(width, height, columnMajor)
            var alpha = if (flags and ALPHA_PLANE != 0) buffer.readPlane(width, height, columnMajor) else null
            if (alpha != null && alpha.all { it == OPAQUE }) {
                alpha = null
            }
            PalettedGraphic(offsetsX[frame], offsetsY[frame], width, height, maxWidth, maxHeight, palette, indices, alpha, flags)
        }
        check(buffer.position() == paletteStart) {
            "Graphic $id pixel data ended at ${buffer.position()}, palette starts at $paletteStart"
        }
    }

    private fun Reader.readPlane(width: Int, height: Int, columnMajor: Boolean): ByteArray {
        val plane = ByteArray(width * height)
        if (columnMajor) {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    plane[x + y * width] = readByte().toByte()
                }
            }
        } else {
            readBytes(plane)
        }
        return plane
    }
}
