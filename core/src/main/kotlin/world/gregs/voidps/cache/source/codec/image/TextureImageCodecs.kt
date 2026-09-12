package world.gregs.voidps.cache.source.codec.image

import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.SourceCodecs

/**
 * The four indices the NXT client keeps its compiled textures in, one per format the graphics
 * backends upload directly, and the layouts that make them files an image tool can open.
 *
 * | index | directory | file |
 * |---|---|---|
 * | 52 | `textures_dxt` | `<archive>.dds` |
 * | 53 | `textures_png` | `<archive>.png`, or `<archive>/<face>.png` |
 * | 54 | `textures_png_mipped` | `<archive>/<level>.png`, or `<archive>/<face>/<level>.png` |
 * | 55 | `textures_etc` | `<archive>.ktx`, or `<archive>/<face>.ktx` |
 *
 * An archive id means the same texture in all four, and the several files are the faces of a cube
 * map, which only a handful of textures are.
 */
object TextureImageCodecs {

    private val dds = byteArrayOf(0x44, 0x44, 0x53, 0x20)

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private val ktx = byteArrayOf(0xab.toByte(), 0x4b, 0x54, 0x58, 0x20, 0x31, 0x31, 0xbb.toByte(), 0x0d, 0x0a, 0x1a, 0x0a)

    val dxt = TextureImageCodec("texture-dds", ".dds", dds)

    val image = TextureImageCodec("texture-png", ".png", png)

    val mipped = TextureImageCodec("texture-png-mipped", ".png", png, mipped = true)

    val etc = TextureImageCodec("texture-ktx", ".ktx", ktx)

    fun register() {
        SourceCodecs.builtin(Index.TEXTURES_DXT, dxt)
        SourceCodecs.builtin(Index.TEXTURES_PNG, image)
        SourceCodecs.builtin(Index.TEXTURES_PNG_MIPPED, mipped)
        SourceCodecs.builtin(Index.TEXTURES_ETC, etc)
    }
}
