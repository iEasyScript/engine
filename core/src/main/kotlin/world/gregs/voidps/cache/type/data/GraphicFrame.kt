package world.gregs.voidps.cache.type.data

import java.awt.image.BufferedImage

internal const val OPAQUE = 0xff.toByte()

sealed class GraphicFrame(val canvasWidth: Int, val canvasHeight: Int) {
    abstract val offsetX: Int
    abstract val offsetY: Int
    abstract val width: Int
    abstract val height: Int

    abstract fun rgba(): ByteArray

    fun toBufferedImage(): BufferedImage? {
        if (width <= 0 || height <= 0) {
            return null
        }
        val rgba = rgba()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = (x + y * width) * 4
                image.setRGB(
                    x,
                    y,
                    ((rgba[pixel + 3].toInt() and 255) shl 24) or
                        ((rgba[pixel].toInt() and 255) shl 16) or
                        ((rgba[pixel + 1].toInt() and 255) shl 8) or
                        (rgba[pixel + 2].toInt() and 255)
                )
            }
        }
        return image
    }
}

class PalettedGraphic(
    override val offsetX: Int,
    override val offsetY: Int,
    override val width: Int,
    override val height: Int,
    canvasWidth: Int,
    canvasHeight: Int,
    val palette: IntArray,
    val indices: ByteArray,
    val alpha: ByteArray?,
    /** The frame's format byte: bit 0 column major storage, bit 1 an alpha plane follows. */
    val flags: Int = 0,
) : GraphicFrame(canvasWidth, canvasHeight) {

    override fun rgba(): ByteArray {
        val rgba = ByteArray(width * height * 4)
        for (pixel in indices.indices) {
            val colour = palette[indices[pixel].toInt() and 255]
            val at = pixel * 4
            if (colour != 0) {
                rgba[at] = (colour shr 16).toByte()
                rgba[at + 1] = (colour shr 8).toByte()
                rgba[at + 2] = colour.toByte()
                rgba[at + 3] = OPAQUE
            }
            if (alpha != null) {
                rgba[at + 3] = alpha[pixel]
            }
        }
        return rgba
    }
}

class RawGraphic(
    override val width: Int,
    override val height: Int,
    val rgb: ByteArray,
    val alpha: ByteArray?,
) : GraphicFrame(width, height) {

    override val offsetX = 0
    override val offsetY = 0

    override fun rgba(): ByteArray {
        val rgba = ByteArray(width * height * 4)
        for (pixel in 0 until width * height) {
            val source = pixel * 3
            val at = pixel * 4
            rgba[at] = rgb[source]
            rgba[at + 1] = rgb[source + 1]
            rgba[at + 2] = rgb[source + 2]
            rgba[at + 3] = alpha?.get(pixel) ?: OPAQUE
        }
        return rgba
    }
}
