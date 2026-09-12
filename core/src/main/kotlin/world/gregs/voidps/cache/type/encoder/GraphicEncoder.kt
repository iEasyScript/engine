package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.PalettedGraphic
import world.gregs.voidps.cache.type.data.RawGraphic
import world.gregs.voidps.cache.type.data.GraphicFrame
import world.gregs.voidps.cache.type.data.GraphicType

/**
 * The exact inverse of `GraphicDecoder`, for both of the layouts a graphic group is stored in.
 *
 * Nothing here decides anything. A palette group writes every frame in the storage order and with
 * the alpha plane its own [PalettedGraphic.flags] calls for, and its palette comes from
 * [GraphicType.rawPalette] rather than from the clamped copy the frames render with; a raw group
 * writes the planes it holds. An alpha plane the decoder dropped because every byte of it was
 * opaque is written back as opaque bytes: that is what "no transparency" means in the file, and
 * the flag is what says the plane is there at all.
 */
class GraphicEncoder : TypeEncoder<GraphicType> {

    override fun Writer.encode(definition: GraphicType) {
        val frames = definition.frames ?: error("Graphic ${definition.id} has no frames to encode.")
        require(frames.size <= FRAME_LIMIT) {
            "Graphic ${definition.id} has ${frames.size} frames; the count is 15 bits."
        }
        if (raw(frames)) {
            encodeRaw(definition, frames)
        } else {
            encodePaletted(definition, frames)
        }
    }

    /** Exactly how many bytes [definition] encodes into. */
    fun size(definition: GraphicType): Int {
        val frames = definition.frames ?: return 0
        var size = TRAILER_BYTES
        if (raw(frames)) {
            size += RAW_HEADER_BYTES
            for (frame in frames) {
                val graphic = frame as RawGraphic
                size += graphic.rgb.size + (graphic.alpha?.size ?: 0)
            }
            return size
        }
        size += CANVAS_BYTES + FRAME_HEADER_BYTES * frames.size +
            maxOf(definition.rawPalette.size - 1, 0) * COLOUR_BYTES
        for (frame in frames) {
            val graphic = frame as PalettedGraphic
            val area = graphic.width * graphic.height
            size += 1 + area + if (graphic.flags and ALPHA_PLANE != 0) area else 0
        }
        return size
    }

    private fun Writer.encodeRaw(definition: GraphicType, frames: Array<GraphicFrame>) {
        val alpha = frames.any { (it as RawGraphic).alpha != null }
        writeByte(RAW_VERSION)
        writeByte(if (alpha) 1 else 0)
        writeShort(definition.maxWidth)
        writeShort(definition.maxHeight)
        for (frame in frames) {
            val graphic = frame as RawGraphic
            require(graphic.width == definition.maxWidth && graphic.height == definition.maxHeight) {
                "Graphic ${definition.id} frame is ${graphic.width}x${graphic.height} but the group is " +
                    "${definition.maxWidth}x${definition.maxHeight}."
            }
            writeBytes(graphic.rgb)
            val plane = graphic.alpha
            require((plane != null) == alpha) {
                "Graphic ${definition.id} mixes frames with and without an alpha plane."
            }
            if (plane != null) {
                writeBytes(plane)
            }
        }
        writeShort(frames.size or RAW_LAYOUT)
    }

    private fun Writer.encodePaletted(definition: GraphicType, frames: Array<GraphicFrame>) {
        for (frame in frames) {
            val graphic = frame as PalettedGraphic
            val area = graphic.width * graphic.height
            require(graphic.indices.size == area) {
                "Graphic ${definition.id} frame is ${graphic.width}x${graphic.height} but holds " +
                    "${graphic.indices.size} pixels."
            }
            writeByte(graphic.flags)
            writePlane(graphic, graphic.indices)
            if (graphic.flags and ALPHA_PLANE == 0) {
                continue
            }
            val plane = graphic.alpha
            if (plane == null) {
                repeat(area) { writeByte(OPAQUE) }
            } else {
                require(plane.size == area) {
                    "Graphic ${definition.id} frame has ${plane.size} alpha bytes for $area pixels."
                }
                writePlane(graphic, plane)
            }
        }

        val palette = definition.rawPalette
        require(palette.isNotEmpty()) { "Graphic ${definition.id} has no palette; entry 0 is always present." }
        require(palette.size <= PALETTE_LIMIT) {
            "Graphic ${definition.id} has ${palette.size} palette entries; the size is a byte plus one."
        }
        for (colour in 1 until palette.size) {
            writeMedium(palette[colour])
        }

        writeShort(definition.maxWidth)
        writeShort(definition.maxHeight)
        writeByte(palette.size - 1)
        for (frame in frames) {
            writeShort(frame.offsetX)
        }
        for (frame in frames) {
            writeShort(frame.offsetY)
        }
        for (frame in frames) {
            writeShort(frame.width)
        }
        for (frame in frames) {
            writeShort(frame.height)
        }
        writeShort(frames.size)
    }

    private fun Writer.writePlane(graphic: PalettedGraphic, plane: ByteArray) {
        if (graphic.flags and COLUMN_MAJOR == 0) {
            writeBytes(plane)
            return
        }
        for (x in 0 until graphic.width) {
            for (y in 0 until graphic.height) {
                writeByte(plane[x + y * graphic.width].toInt())
            }
        }
    }

    private fun raw(frames: Array<GraphicFrame>): Boolean = frames.isNotEmpty() && frames[0] is RawGraphic

    private companion object {
        const val RAW_LAYOUT = 0x8000

        const val FRAME_LIMIT = 0x7fff

        /** The raw layout leads with a version byte the client requires to be 0. */
        const val RAW_VERSION = 0

        const val COLUMN_MAJOR = 0x1

        const val ALPHA_PLANE = 0x2

        const val OPAQUE = 0xff

        /** The palette size is stored as `size - 1` in one byte. */
        const val PALETTE_LIMIT = 256

        const val TRAILER_BYTES = 2

        const val RAW_HEADER_BYTES = 6

        const val CANVAS_BYTES = 5

        const val FRAME_HEADER_BYTES = 8

        const val COLOUR_BYTES = 3
    }
}
