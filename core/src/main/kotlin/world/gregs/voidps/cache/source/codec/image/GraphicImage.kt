package world.gregs.voidps.cache.source.codec.image

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * One graphic frame as one standard PNG, and everything a byte exact encoder needs written inside
 * the same file.
 *
 * The picture is 8 bit RGBA truecolour at the group's canvas size - the canvas the client composes
 * every frame of the group onto - with the frame painted at its own offset and the rest fully
 * transparent, so the pixels alone say where the frame is, what colours it uses and how opaque each
 * one is. What they under-determine rides in three private ancillary PNG chunks every decoder
 * ignores:
 *
 * | chunk | scope | holds |
 * |---|---|---|
 * | `rsPL` | the group | the palette exactly as the file spells it, entry 0 first |
 * | `rsFR` | the frame | the layout, the ids, the canvas, the frame's box, the format byte and which shape its alpha plane is |
 * | `rsIX` | the frame | the palette index raster, only where the colours cannot recover it |
 *
 * All three are lowercase-first (ancillary), lowercase-second (private) and uppercase-fourth,
 * PNG's "unsafe to copy": the type itself tells an editor that rewrites the pixels to drop the
 * chunk rather than carry a stale one forward. Each carries a version byte so a future shape is
 * refused rather than misread, and an editor that strips them entirely - which is most of them -
 * costs only the byte exactness of what the pixels under-determine, never the picture.
 *
 * The PNG is written through an in-memory stream rather than `ImageIO.write`, which otherwise
 * spills to a temporary file per image, and the chunks are spliced in straight after `IHDR` so the
 * file is a deterministic byte sequence for a given frame - re-unpacking an unchanged cache must
 * rewrite nothing.
 */
internal object GraphicImage {

    /** The group's palette, as `rsPL` carries it. */
    class Palette(val entries: IntArray)

    /** One frame's layout, placement and format, as `rsFR` carries it. */
    class Frame(
        /** [PALETTED] or [RAW]. */
        val layout: Int,
        val archive: Int,
        val frame: Int,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val minX: Int,
        val minY: Int,
        val width: Int,
        val height: Int,
        /** The palette frame's format byte; 0 for a raw frame. */
        val flags: Int,
        /** [NO_PLANE], [PLANE] or [OPAQUE_PLANE]. */
        val alpha: Int
    )

    /** `rsIX`'s payload: the frame's palette indices, row major over the frame's own box. */
    class Raster(val width: Int, val height: Int, val values: ByteArray)

    /** A frame PNG as it is on disk: the pixels, and whichever of the three chunks survived. */
    class Image(
        val width: Int,
        val height: Int,
        /** Non-premultiplied ARGB, row major, [width] x [height]. */
        val pixels: IntArray,
        val palette: Palette?,
        val frame: Frame?,
        val indices: Raster?
    )

    /** The group's frames are a shared palette and a box each. */
    const val PALETTED = 0

    /** The group's frames are full canvas RGB, with one alpha plane shape for the whole group. */
    const val RAW = 1

    /** The frame has no alpha plane, so index 0 is its only transparency. */
    const val NO_PLANE = 0

    /** The frame carries an alpha plane and the PNG's alpha channel is that plane. */
    const val PLANE = 1

    /**
     * The frame carries an alpha plane every byte of which is opaque, which the palette decoder
     * (and the client) drops - so only this says the plane was there.
     */
    const val OPAQUE_PLANE = 2

    /**
     * A frame painted onto [canvasWidth] x [canvasHeight] of transparency, with the chunks that
     * describe what the pixels leave open.
     *
     * [pixels] is the frame's own box in non-premultiplied ARGB, row major.
     */
    fun write(
        canvasWidth: Int,
        canvasHeight: Int,
        frame: Frame,
        pixels: IntArray,
        palette: IntArray?,
        raster: ByteArray?
    ): ByteArray {
        val image = BufferedImage(maxOf(canvasWidth, 1), maxOf(canvasHeight, 1), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                image.setRGB(frame.minX + x, frame.minY + y, pixels[x + y * frame.width])
            }
        }
        val chunks = ArrayList<ByteArray>(3)
        if (palette != null) {
            chunks.add(chunk(PALETTE_CHUNK, palette(palette)))
        }
        chunks.add(chunk(FRAME_CHUNK, frame(frame)))
        if (raster != null) {
            chunks.add(chunk(INDEX_CHUNK, indices(frame, raster)))
        }
        return splice(png(image), chunks)
    }

    /** [bytes] as its pixels and its chunks; [name] names the file in any error. */
    fun read(bytes: ByteArray, name: String): Image {
        val image = decode(bytes, name)
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        var palette: Palette? = null
        var frame: Frame? = null
        var indices: Raster? = null
        for ((type, payload) in chunks(bytes, name)) {
            when (type) {
                PALETTE_CHUNK -> palette = palette(payload, name)
                FRAME_CHUNK -> frame = frame(payload, name)
                INDEX_CHUNK -> indices = indices(payload, name)
            }
        }
        return Image(image.width, image.height, pixels, palette, frame, indices)
    }

    /** The palette the client renders: entry 0 apart, an entry that really is black becomes 1. */
    fun normalise(raw: IntArray): IntArray {
        val palette = raw.copyOf()
        for (index in 1 until palette.size) {
            if (palette[index] == 0) {
                palette[index] = 1
            }
        }
        return palette
    }

    /**
     * Colour to palette index, for entries 1 and up: the normalised colour first, then the raw one
     * where nothing claims it, and the lowest index wins a tie.
     *
     * Entry 0 is deliberately unreachable - it is the transparent index and a fully transparent
     * pixel is the only thing that recovers it - so an opaque pixel that really is black lands on
     * whichever entry the file spells `000000` and the client renders as 1.
     */
    fun lookup(raw: IntArray, palette: IntArray): MutableMap<Int, Int> {
        val out = HashMap<Int, Int>(palette.size * 2)
        for (index in 1 until palette.size) {
            out.putIfAbsent(palette[index], index)
        }
        for (index in 1 until raw.size) {
            out.putIfAbsent(raw[index], index)
        }
        return out
    }

    private fun palette(entries: IntArray): ByteArray {
        val out = ByteArray(PALETTE_HEADER + entries.size * COLOUR)
        out[0] = VERSION
        write16(out, 1, entries.size)
        for (index in entries.indices) {
            val offset = PALETTE_HEADER + index * COLOUR
            out[offset] = (entries[index] shr 16).toByte()
            out[offset + 1] = (entries[index] shr 8).toByte()
            out[offset + 2] = entries[index].toByte()
        }
        return out
    }

    private fun palette(payload: ByteArray, name: String): Palette {
        if (payload.size < PALETTE_HEADER || payload[0] != VERSION) {
            throw IOException("$name's $PALETTE_CHUNK chunk is not version $VERSION.")
        }
        val size = read16(payload, 1)
        if (size < 1 || PALETTE_HEADER + size * COLOUR != payload.size) {
            throw IOException("$name's $PALETTE_CHUNK chunk says $size entries but is ${payload.size} bytes.")
        }
        val entries = IntArray(size)
        for (index in 0 until size) {
            val offset = PALETTE_HEADER + index * COLOUR
            entries[index] = ((payload[offset].toInt() and 0xff) shl 16) or
                ((payload[offset + 1].toInt() and 0xff) shl 8) or
                (payload[offset + 2].toInt() and 0xff)
        }
        return Palette(entries)
    }

    private fun frame(frame: Frame): ByteArray {
        val out = ByteArray(FRAME_SIZE)
        out[0] = VERSION
        out[1] = frame.layout.toByte()
        write32(out, 2, frame.archive)
        write16(out, 6, frame.frame)
        write16(out, 8, frame.canvasWidth)
        write16(out, 10, frame.canvasHeight)
        write16(out, 12, frame.minX)
        write16(out, 14, frame.minY)
        write16(out, 16, frame.width)
        write16(out, 18, frame.height)
        out[20] = frame.flags.toByte()
        out[21] = frame.alpha.toByte()
        return out
    }

    private fun frame(payload: ByteArray, name: String): Frame {
        if (payload.size != FRAME_SIZE || payload[0] != VERSION) {
            throw IOException("$name's $FRAME_CHUNK chunk is not $FRAME_SIZE bytes of version $VERSION.")
        }
        return Frame(
            layout = payload[1].toInt() and 0xff,
            archive = read32(payload, 2),
            frame = read16(payload, 6),
            canvasWidth = read16(payload, 8),
            canvasHeight = read16(payload, 10),
            minX = read16(payload, 12),
            minY = read16(payload, 14),
            width = read16(payload, 16),
            height = read16(payload, 18),
            flags = payload[20].toInt() and 0xff,
            alpha = payload[21].toInt() and 0xff
        )
    }

    private fun indices(frame: Frame, raster: ByteArray): ByteArray {
        val out = ByteArray(RASTER_HEADER + raster.size)
        out[0] = VERSION
        write16(out, 1, frame.width)
        write16(out, 3, frame.height)
        raster.copyInto(out, RASTER_HEADER)
        return out
    }

    private fun indices(payload: ByteArray, name: String): Raster {
        if (payload.size < RASTER_HEADER || payload[0] != VERSION) {
            throw IOException("$name's $INDEX_CHUNK chunk is not version $VERSION.")
        }
        val width = read16(payload, 1)
        val height = read16(payload, 3)
        if (RASTER_HEADER + width * height != payload.size) {
            throw IOException("$name's $INDEX_CHUNK chunk says ${width}x$height but is ${payload.size} bytes.")
        }
        return Raster(width, height, payload.copyOfRange(RASTER_HEADER, payload.size))
    }

    /** [type] and [payload] as a PNG chunk, length and CRC and all. */
    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(CHUNK_OVERHEAD + payload.size)
        write32(out, 0, payload.size)
        for (index in 0 until 4) {
            out[4 + index] = type[index].code.toByte()
        }
        payload.copyInto(out, 8)
        val crc = CRC32()
        crc.update(out, 4, 4 + payload.size)
        write32(out, 8 + payload.size, crc.value.toInt())
        return out
    }

    /** [png] with [chunks] inserted straight after its `IHDR`. */
    private fun splice(png: ByteArray, chunks: List<ByteArray>): ByteArray {
        val insert = SIGNATURE + CHUNK_OVERHEAD + IHDR_SIZE
        if (png.size < insert) {
            throw IOException("A PNG this build wrote is ${png.size} bytes, too short for an IHDR.")
        }
        val out = ByteArrayOutputStream(png.size + chunks.sumOf { it.size })
        out.write(png, 0, insert)
        for (chunk in chunks) {
            out.write(chunk)
        }
        out.write(png, insert, png.size - insert)
        return out.toByteArray()
    }

    /** Every chunk of [bytes] this cares about, in file order, as type to payload. */
    private fun chunks(bytes: ByteArray, name: String): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>(3)
        var position = SIGNATURE
        while (position + 8 <= bytes.size) {
            val length = read32(bytes, position)
            if (length < 0 || position + CHUNK_OVERHEAD + length > bytes.size) {
                throw IOException("$name has a chunk at $position that runs past the end of the file.")
            }
            val type = String(bytes, position + 4, 4, Charsets.US_ASCII)
            if (type in CHUNKS) {
                out.add(type to bytes.copyOfRange(position + 8, position + 8 + length))
            }
            position += CHUNK_OVERHEAD + length
        }
        return out
    }

    /**
     * The image, through a reader this owns.
     *
     * `ImageIO.read` disposes the stream it is handed, so the stream cannot also be closed by the
     * caller; driving the reader directly keeps both halves of this file symmetrical.
     */
    private fun decode(bytes: ByteArray, name: String): BufferedImage {
        val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        val readers = ImageIO.getImageReaders(stream)
        if (!readers.hasNext()) {
            throw IOException("$name is not an image this build can read.")
        }
        val reader = readers.next()
        try {
            reader.input = stream
            return reader.read(0)
        } finally {
            reader.dispose()
            stream.close()
        }
    }

    private fun png(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream(image.width * image.height + HEADER)
        val writer = ImageIO.getImageWritersByFormatName(FORMAT).next()
        try {
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(image)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    private fun write16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value shr 8).toByte()
        out[offset + 1] = value.toByte()
    }

    private fun write32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value shr 24).toByte()
        out[offset + 1] = (value shr 16).toByte()
        out[offset + 2] = (value shr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun read16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun read32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)

    private const val PALETTE_CHUNK = "rsPL"

    private const val FRAME_CHUNK = "rsFR"

    private const val INDEX_CHUNK = "rsIX"

    private val CHUNKS = setOf(PALETTE_CHUNK, FRAME_CHUNK, INDEX_CHUNK)

    /** Every chunk this writes starts with its own version byte, so a reader can refuse a newer one. */
    private const val VERSION: Byte = 1

    private const val FRAME_SIZE = 22

    private const val PALETTE_HEADER = 3

    private const val RASTER_HEADER = 5

    private const val COLOUR = 3

    /** A chunk's length, type and CRC. */
    private const val CHUNK_OVERHEAD = 12

    /** The PNG signature, which every chunk sits after. */
    private const val SIGNATURE = 8

    /** `IHDR`'s payload: width, height, depth, colour, compression, filter, interlace. */
    private const val IHDR_SIZE = 13

    private const val FORMAT = "png"

    /** Roughly what a PNG's chunks cost before any pixels. */
    private const val HEADER = 1024
}
