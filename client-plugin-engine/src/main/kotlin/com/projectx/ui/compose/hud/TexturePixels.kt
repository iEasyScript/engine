package com.projectx.ui.compose.hud

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.projectx.ui.backend.native.ImGuiTexture
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.Collections
import java.util.WeakHashMap

/**
 * The pixels behind each texture, so Compose can draw what scripts and overlays hand ImGui by texture.
 *
 * A texture is a GPU handle Compose cannot read, so its source pixels are kept beside it for as long as the texture
 * lives and turned into an [ImageBitmap] the first time one is drawn.
 */
object TexturePixels {
    private class Source(val rgba: ByteArray, val width: Int, val height: Int) {
        @Volatile var bitmap: ImageBitmap? = null
    }

    private val sources = Collections.synchronizedMap(WeakHashMap<ImGuiTexture, Source>())

    fun keep(texture: ImGuiTexture, rgba: ByteArray, width: Int, height: Int) {
        sources[texture] = Source(rgba, width, height)
    }

    fun bitmap(texture: ImGuiTexture): ImageBitmap? {
        val source = sources[texture] ?: return null
        source.bitmap?.let { return it }
        return runCatching {
            val bitmap = Bitmap().apply {
                allocPixels(ImageInfo(source.width, source.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
                installPixels(source.rgba)
            }
            Image.makeFromBitmap(bitmap).toComposeImageBitmap()
        }.getOrNull()?.also { source.bitmap = it }
    }
}
