package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import java.awt.image.BufferedImage

data class GraphicType(
    override var id: Int = -1,
    var frames: Array<GraphicFrame>? = null,
    var maxWidth: Int = 0,
    var maxHeight: Int = 0,
    /** The palette exactly as the file spells it, entry 0 first, before the client's clamp. */
    var rawPalette: IntArray = IntArray(0),
) : CacheType {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GraphicType

        if (id != other.id) return false
        if (maxWidth != other.maxWidth) return false
        if (maxHeight != other.maxHeight) return false
        if (!rawPalette.contentEquals(other.rawPalette)) return false
        if (!frames.contentEquals(other.frames)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + maxWidth
        result = 31 * result + maxHeight
        result = 31 * result + rawPalette.contentHashCode()
        result = 31 * result + (frames?.contentHashCode() ?: 0)
        return result
    }
}

fun BufferedImage.toRGBABytes(): ByteArray {
    val pixels = IntArray(width * height)
    getRGB(0, 0, width, height, pixels, 0, width)
    val rgba = ByteArray(width * height * 4)
    var i = 0
    for (argb in pixels) {
        rgba[i++] = ((argb shr 16) and 0xFF).toByte()
        rgba[i++] = ((argb shr 8) and 0xFF).toByte()
        rgba[i++] = (argb and 0xFF).toByte()
        rgba[i++] = ((argb shr 24) and 0xFF).toByte()
    }
    return rgba
}
