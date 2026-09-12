package com.projectx.ui.backend.native

import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Paths
import java.io.InputStream
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

object ImageHelper {
    data class DecodedImage(val pixels: ByteArray, val width: Int, val height: Int)

    private val textureCache = ConcurrentHashMap<String, WeakReference<ImGuiTexture>>()

    fun getNoiseTexture(): ImGuiTexture {
        val key = "__imghelper_noise_128__"
        val cached = textureCache[key]?.get()
        if (cached != null) return cached

        val width = 128
        val height = 128
        val pixels = ByteArray(width * height * 4)

        val rnd = Random(1337L)
        var i = 0
        while (i < pixels.size) {
            pixels[i++] = 0xFF.toByte()
            pixels[i++] = 0xFF.toByte()
            pixels[i++] = 0xFF.toByte()
            pixels[i++] = (rnd.nextInt(64)).toByte()
        }

        val tex = NativeBridge.createTextureFromRGBA(pixels, width, height)
            ?: run {
                val tiny = staticTestImageRGBA()
                NativeBridge.createTextureFromRGBA(tiny.pixels, tiny.width, tiny.height)
            }
            ?: error("Failed to create noise texture")

        textureCache[key] = WeakReference(tex)
        return tex
    }

    fun staticTestImageRGBA(): DecodedImage {
        val w = 2
        val h = 2
        val p = ByteArray(w * h * 4)
        var i = 0
        p[i++] = 255.toByte(); p[i++] = 0.toByte();   p[i++] = 0.toByte();   p[i++] = 255.toByte()
        p[i++] = 0.toByte();   p[i++] = 255.toByte(); p[i++] = 0.toByte();   p[i++] = 255.toByte()
        p[i++] = 0.toByte();   p[i++] = 0.toByte();   p[i++] = 255.toByte(); p[i++] = 255.toByte()
        p[i++] = 255.toByte(); p[i++] = 255.toByte(); p[i++] = 255.toByte(); p[i++] = 255.toByte()
        return DecodedImage(p, w, h)
    }

    fun loadStaticTestTexture(): ImGuiTexture? {
        val test = staticTestImageRGBA()
        return loadTextureFromRGBA(test.pixels, test.width, test.height, cacheKey = "__imghelper_static_test__")
    }

    /** If cacheKey is null, no caching is performed. Create outside of the render method. */
    fun loadTextureFromRGBA(pixels: ByteArray, width: Int, height: Int, cacheKey: String? = null): ImGuiTexture? {
        if (cacheKey != null) {
            val ref = textureCache[cacheKey]
            val cached = ref?.get()
            if (cached != null) return cached
            if (ref != null) {
                textureCache.remove(cacheKey, ref)
            }
        }

        val tex = NativeBridge.createTextureFromRGBA(pixels, width, height)
        if (tex != null && cacheKey != null) {
            textureCache[cacheKey] = WeakReference(tex)
        }
        return tex
    }

    fun loadTexture(pathOrResource: String): ImGuiTexture? {
        val ref = textureCache[pathOrResource]
        val cached = ref?.get()
        if (cached != null) return cached
        if (ref != null) {
            textureCache.remove(pathOrResource, ref)
        }

        val decoded = decodeImageToRGBA(pathOrResource) ?: return null
        val tex = NativeBridge.createTextureFromRGBA(decoded.pixels, decoded.width, decoded.height)
        if (tex != null) {
            textureCache[pathOrResource] = WeakReference(tex)
        }
        return tex
    }

    fun forgetTextureById(textureId: Long) {
        if (textureId == 0L) return
        textureCache.entries.removeIf { entry ->
            val tex = entry.value.get()
            tex == null || tex.id == textureId
        }
    }

    private fun decodeImageToRGBA(original: String): DecodedImage? {
        try {
            val p = Paths.get(original)
            if (Files.exists(p)) {
                Files.newInputStream(p).use { input ->
                    return decodeStream(input)
                }
            }
        } catch (_: Throwable) {}

        val candidates = ArrayList<String>(4)
        if (original.contains("!")) {
            val afterBang = original.substringAfter('!')
            val jarRes = if (afterBang.startsWith('/')) afterBang else "/$afterBang"
            candidates.add(jarRes)
        }
        candidates.add(if (original.startsWith('/')) original else "/$original")
        candidates.add(original.removePrefix("/"))

        val loader = ImageHelper::class.java.classLoader
        for (cand in candidates) {
            val url = ImageHelper::class.java.getResource(cand) ?: loader?.getResource(cand.removePrefix("/"))
            if (url != null) {
                try {
                    url.openStream().use { input ->
                        return decodeStream(input)
                    }
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    private fun decodeStream(input: InputStream): DecodedImage? {
        return try {
            val image = ImageIO.read(input) ?: return null
            val w = image.width
            val h = image.height
            val argb = IntArray(w * h)
            image.getRGB(0, 0, w, h, argb, 0, w)
            val out = ByteArray(w * h * 4)
            var i = 0
            var j = 0
            while (i < argb.size) {
                val v = argb[i]
                out[j]     = ((v ushr 16) and 0xFF).toByte()
                out[j + 1] = ((v ushr 8) and 0xFF).toByte()
                out[j + 2] = (v and 0xFF).toByte()
                out[j + 3] = ((v ushr 24) and 0xFF).toByte()
                i++
                j += 4
            }
            DecodedImage(out, w, h)
        } catch (_: Throwable) {
            null
        }
    }

    fun decode(original: String): DecodedImage? = decodeImageToRGBA(original)
}
