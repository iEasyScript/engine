package com.projectx.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import com.projectx.ui.backend.native.ImGuiTexture
import com.projectx.ui.backend.native.NativeBridge
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.lang.foreign.MemorySegment

/** Which surface a composable is drawn on, so keyboard focus can tell one surface's coordinates from another's. */
val LocalSurface = staticCompositionLocalOf { "" }

/**
 * One Compose scene rendered offscreen into a texture ImGui can draw.
 *
 * The scene is as large as the display and [render] lays it out at the size asked for, so resizing relays the
 * content out instead of rebuilding it. Only the requested area is read back and uploaded, and only on frames where
 * something in the scene changed. Render thread only.
 */
class ComposeSurface(private val name: String, private val content: @Composable () -> Unit) {
    private var scene: ImageComposeScene? = null
    private var sceneWidth = 0
    private var sceneHeight = 0
    private var pixels: Bitmap? = null

    var texture: ImGuiTexture? = null
        private set

    private var inside = false
    private var pressed = false
    private var lastPointer = Offset.Unspecified

    /** Whether the mouse was over this surface when it was last presented; set by whoever presents it. */
    var hovered = false

    /** The part of the scene the texture holds; the whole laid-out area unless [render] was given a crop. */
    var region = IntRect.Zero
        private set

    /**
     * Lays the scene out at [width] x [height], passes the pointer on, and re-renders if anything changed. Only [crop]
     * is read back and uploaded - for a mostly empty full-screen layer, that is most of the cost saved.
     */
    fun render(width: Int, height: Int, pointer: Offset, down: Boolean, wheel: Float, crop: IntRect = IntRect(0, 0, width, height)): ImGuiTexture? {
        val scene = sceneFor(NativeBridge.getDisplaySize())
        val size = Constraints.fixed(width, height)
        if (scene.constraints != size) scene.constraints = size
        forward(scene, pointer, down, wheel)
        if (texture == null || crop != region || scene.hasInvalidations()) {
            scene.render(System.nanoTime()).use { upload(it, crop) }
            region = crop
        }
        return texture
    }

    fun release() {
        val scene = scene ?: return
        if (pressed) scene.sendPointerEvent(PointerEventType.Release, lastPointer, buttons = PointerButtons(), button = PointerButton.Primary)
        if (inside) scene.sendPointerEvent(PointerEventType.Exit, lastPointer)
        pressed = false
        inside = false
        hovered = false
    }

    fun dispose() {
        scene?.close()
        scene = null
        // A new scene has seen no press or hover yet; carried over, the first frame would send it a stray release.
        pressed = false
        inside = false
        lastPointer = Offset.Unspecified
        texture?.destroy()
        texture = null
        pixels?.close()
        pixels = null
    }

    private fun sceneFor(display: Pair<Float, Float>): ImageComposeScene {
        val w = display.first.toInt().coerceAtLeast(MIN_SCENE)
        val h = display.second.toInt().coerceAtLeast(MIN_SCENE)
        scene?.let { if (w <= sceneWidth && h <= sceneHeight) return it }
        dispose()
        sceneWidth = w
        sceneHeight = h
        return ImageComposeScene(w, h, Density(1f)) {
            CompositionLocalProvider(LocalSurface provides name) { content() }
        }.also { scene = it }
    }

    private fun forward(scene: ImageComposeScene, position: Offset, down: Boolean, wheel: Float) {
        // A press that started here keeps the pointer until release, so a drag that leaves the surface (a
        // scrollbar, a slider) still ends where the user let go.
        val tracking = hovered || pressed
        if (tracking && !inside) {
            scene.sendPointerEvent(PointerEventType.Enter, position, buttons = PointerButtons(isPrimaryPressed = down))
            inside = true
        }
        if (tracking && position != lastPointer) {
            scene.sendPointerEvent(PointerEventType.Move, position, buttons = PointerButtons(isPrimaryPressed = down))
        }
        if (hovered && down && !pressed) {
            pressed = true
            scene.sendPointerEvent(PointerEventType.Press, position, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        } else if (pressed && !down) {
            pressed = false
            scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Primary)
        }
        if (hovered && wheel != 0f) scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = Offset(0f, -wheel))
        if (!hovered && !pressed && inside) {
            scene.sendPointerEvent(PointerEventType.Exit, position)
            inside = false
        }
        lastPointer = position
    }

    private fun upload(image: Image, crop: IntRect) {
        val width = crop.width
        val height = crop.height
        if (width <= 0 || height <= 0) return
        val bitmap = pixels?.takeIf { it.width == width && it.height == height }
            ?: Bitmap().apply { allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)) }.also {
                pixels?.close()
                pixels = it
            }
        image.readPixels(bitmap, crop.left, crop.top)
        val current = texture?.takeIf { it.width == width && it.height == height }
        if (current != null && NativeBridge.canUpdateTextures) {
            val address = bitmap.peekPixels()?.use { it.addr } ?: return
            if (NativeBridge.updateTextureFromRGBA(current, MemorySegment.ofAddress(address).reinterpret(width.toLong() * height * 4))) return
        }
        // A new size, or a bootstrap without in-place updates, needs a new texture - only on frames that changed.
        val rgba = bitmap.readPixels() ?: return
        val next = NativeBridge.createTransientTextureFromRGBA(rgba, width, height) ?: return
        texture?.destroy()
        texture = next
    }

    private companion object {
        const val MIN_SCENE = 1024
    }
}
