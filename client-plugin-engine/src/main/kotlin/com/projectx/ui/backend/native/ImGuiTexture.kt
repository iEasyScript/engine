package com.projectx.ui.backend.native

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.GraphicType
import world.gregs.voidps.cache.type.data.toRGBABytes
import java.awt.image.BufferedImage
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Managed ImGui texture that auto-destroys via Cleaner when unreachable.
 * Thread-safe single-shot destruction is ensured by an AtomicBoolean gate.
 */
class ImGuiTexture internal constructor(
    val id: Long,
    val width: Int,
    val height: Int
) : AutoCloseable {

    private val disposed = AtomicBoolean(false)
    private val cleanable: Cleaner.Cleanable = cleaner.register(this, Cleanup(id, disposed))

    fun destroy() {
        cleanable.clean()
    }

    override fun close() = destroy()

    private class Cleanup(
        private val textureId: Long,
        private val disposed: AtomicBoolean
    ) : Runnable {
        override fun run() {
            if (textureId == 0L) return
            if (disposed.compareAndSet(false, true)) {
                try {
                    NativeBridge.destroyTextureById(textureId)
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        private val cleaner: Cleaner = Cleaner.create()

        fun fromRGBA(pixels: ByteArray, width: Int, height: Int): ImGuiTexture? =
            NativeBridge.createTextureFromRGBA(pixels, width, height)

        fun fromPath(pathOrResource: String): ImGuiTexture? =
            ImageHelper.loadTexture(pathOrResource)
    }
}

private data class GraphicKey(val id: Int, val turns: Int)
private val graphicTextureCache = mutableMapOf<GraphicKey, ImGuiTexture>()

enum class GraphicRotation(val turns: Int) {
    R0(0), R90(1), R180(2), R270(3);
    val degrees: Int get() = turns * 90
}

/** Skill/UI graphic ids. */
object GraphicIds {
    const val MINING = 13198
    const val CRAFTING = 13203
    const val RUNECRAFTING = 13206
    const val WOODCUTTING = 13207
    const val SMITHING = 13208
    const val FARMING = 13213
    const val DUNGEONEERING = 13215
    const val DIVINATION_HIRES = 20342
}

/** Decode [id] from the shared cache and build its ImGui texture, erroring if absent. */
fun graphicTexture(id: Int, rotation: GraphicRotation = GraphicRotation.R0): ImGuiTexture =
    (Cache.graphic(id) ?: error("Graphic $id doesn't exist.")).getTexture(rotation)

fun GraphicType.getTexture(): ImGuiTexture = getTexture(GraphicRotation.R0)

fun GraphicType.getTexture(rotation: GraphicRotation): ImGuiTexture =
    graphicTextureCache.getOrPut(GraphicKey(id, rotation.turns)) {
        createTexture(rotation) ?: error("Graphic $id doesn't exist.")
    }

fun GraphicType.createTexture(rotation: GraphicRotation): ImGuiTexture? {
    val subs = frames ?: return null
    if (subs.isEmpty() || maxWidth <= 0 || maxHeight <= 0) return null

    val fullImage = BufferedImage(maxWidth, maxHeight, BufferedImage.TYPE_INT_ARGB)
    val graphics = fullImage.createGraphics()

    for (sub in subs) {
        val image = sub.toBufferedImage() ?: continue
        graphics.drawImage(image, sub.offsetX, sub.offsetY, null)
    }
    graphics.dispose()

    val (finalImage, w, h) = when (rotation) {
        GraphicRotation.R0 -> Triple(fullImage, fullImage.width, fullImage.height)
        GraphicRotation.R90 -> rotate90(fullImage)
        GraphicRotation.R180 -> rotate180(fullImage)
        GraphicRotation.R270 -> rotate270(fullImage)
    }

    val rgbaBytes = finalImage.toRGBABytes()
    return ImGuiTexture.fromRGBA(rgbaBytes, w, h)
}

fun GraphicType.clearTextureCache() {
    val keys = graphicTextureCache.keys.filter { it.id == id }
    keys.forEach { key -> graphicTextureCache.remove(key)?.destroy() }
}

fun clearAllGraphicTextures() {
    graphicTextureCache.values.forEach { it.destroy() }
    graphicTextureCache.clear()
}

private fun rotate90(src: BufferedImage): Triple<BufferedImage, Int, Int> {
    val w = src.width
    val h = src.height
    val dst = BufferedImage(h, w, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val argb = src.getRGB(x, y)
            dst.setRGB(h - 1 - y, x, argb)
        }
    }
    return Triple(dst, h, w)
}

private fun rotate180(src: BufferedImage): Triple<BufferedImage, Int, Int> {
    val w = src.width
    val h = src.height
    val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val argb = src.getRGB(x, y)
            dst.setRGB(w - 1 - x, h - 1 - y, argb)
        }
    }
    return Triple(dst, w, h)
}

private fun rotate270(src: BufferedImage): Triple<BufferedImage, Int, Int> {
    val w = src.width
    val h = src.height
    val dst = BufferedImage(h, w, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val argb = src.getRGB(x, y)
            dst.setRGB(y, w - 1 - x, argb)
        }
    }
    return Triple(dst, h, w)
}
