package com.projectx.ui.compose

import androidx.compose.ui.geometry.Offset
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.util.Configuration

/**
 * The main panel: a Compose surface drawn by a borderless ImGui window, which is what keeps clicks on it from reaching
 * the game. Remembers where it was left and how big it was. Render thread only, between NewFrame and Render.
 */
object ComposeOverlay {
    private const val DEFAULT_WIDTH = 1040
    private const val DEFAULT_HEIGHT = 680
    private const val MIN_WIDTH = 860
    private const val MIN_HEIGHT = 540
    private const val MARGIN = 24

    private const val WINDOW_ID = "##projectx-compose"
    private const val PRIMARY = 0

    val visible: Boolean get() = UIState.showMainWindow.value

    val hovered: Boolean get() = surface.hovered

    private val surface = ComposeSurface("panel") { OverlayApp() }

    private var x = MARGIN.toFloat()
    private var y = MARGIN.toFloat()
    private var width = DEFAULT_WIDTH
    private var height = DEFAULT_HEIGHT
    private var boundsLoaded = false

    private var failures = 0

    private var dragAnchor: Offset? = null
    private var resizeAnchor: Offset? = null

    fun drawFrame(wheel: Float) {
        if (!visible) {
            surface.release()
            dragAnchor = null
            resizeAnchor = null
            return
        }
        loadBounds()
        followDrag()
        followResize()
        val (mx, my) = NativeBridge.getMousePos()
        try {
            surface.render(width, height, Offset(mx - x, my - y), NativeBridge.isMouseDown(PRIMARY), wheel)
        } catch (t: Throwable) {
            rebuildAfter(t)
            return
        }
        present()
    }

    /**
     * Drawing the panel threw. Compose cancels a scene's recomposer when composition throws, so the scene would keep
     * showing its last frame and never react again; it is thrown away instead and the next frame builds a fresh one.
     * The first failure is logged in full, repeats in one line, so a panel that keeps failing cannot flood the log.
     */
    private fun rebuildAfter(t: Throwable) {
        surface.dispose()
        failures++
        if (failures == 1) {
            println("[Overlay] The panel threw while drawing and was rebuilt: ${t::class.simpleName}: ${t.message}")
            t.printStackTrace()
        } else {
            println("[Overlay] The panel threw again ($failures times) and was rebuilt: ${t::class.simpleName}: ${t.message}")
        }
    }

    /** Starts moving the panel with the mouse; called by the header when it is pressed. */
    fun beginDrag() {
        val (mx, my) = NativeBridge.getMousePos()
        dragAnchor = Offset(mx - x, my - y)
    }

    /** Starts resizing from the bottom-right corner; called by the grip when it is pressed. */
    fun beginResize() {
        val (mx, my) = NativeBridge.getMousePos()
        resizeAnchor = Offset(x + width - mx, y + height - my)
    }

    fun hide() {
        UIState.showMainWindow.value = false
    }

    fun resetBounds() {
        x = MARGIN.toFloat()
        y = MARGIN.toFloat()
        width = DEFAULT_WIDTH
        height = DEFAULT_HEIGHT
        saveBounds()
    }

    fun dispose() = surface.dispose()

    private fun loadBounds() {
        if (boundsLoaded) return
        boundsLoaded = true
        Configuration.config.overlayBounds?.takeIf { it.size == 4 }?.let { (bx, by, bw, bh) ->
            x = bx.toFloat()
            y = by.toFloat()
            width = bw
            height = bh
        }
        clampToDisplay()
    }

    private fun saveBounds() {
        Configuration.updateConfig(Configuration.config.copy(overlayBounds = listOf(x.toInt(), y.toInt(), width, height)))
    }

    private fun clampToDisplay() {
        val (displayWidth, displayHeight) = NativeBridge.getDisplaySize()
        width = width.coerceIn(MIN_WIDTH, displayWidth.toInt().coerceAtLeast(MIN_WIDTH))
        height = height.coerceIn(MIN_HEIGHT, displayHeight.toInt().coerceAtLeast(MIN_HEIGHT))
        x = x.coerceIn(0f, (displayWidth - width).coerceAtLeast(0f))
        y = y.coerceIn(0f, (displayHeight - height).coerceAtLeast(0f))
    }

    private fun followDrag() {
        val anchor = dragAnchor ?: return
        val (mx, my) = NativeBridge.getMousePos()
        x = mx - anchor.x
        y = my - anchor.y
        clampToDisplay()
        if (!NativeBridge.isMouseDown(PRIMARY)) {
            dragAnchor = null
            saveBounds()
        }
    }

    private fun followResize() {
        val anchor = resizeAnchor ?: return
        val (mx, my) = NativeBridge.getMousePos()
        width = (mx + anchor.x - x).toInt()
        height = (my + anchor.y - y).toInt()
        clampToDisplay()
        if (!NativeBridge.isMouseDown(PRIMARY)) {
            resizeAnchor = null
            saveBounds()
        }
    }

    private fun present() {
        val texture = surface.texture ?: return
        NativeBridge.setNextWindowPos(x, y)
        NativeBridge.setNextWindowSize(width.toFloat(), height.toFloat())
        NativeBridge.pushStyleVarVec2(ImGuiStyleVar.WindowPadding, 0f, 0f)
        NativeBridge.pushStyleVarFloat(ImGuiStyleVar.WindowBorderSize, 0f)
        NativeBridge.pushStyleVarFloat(ImGuiStyleVar.WindowRounding, 0f)
        NativeBridge.pushStyleColor(ImGuiCol.WindowBg, 0)
        try {
            if (NativeBridge.begin(WINDOW_ID, null, WINDOW_FLAGS)) {
                // Drawn at the panel's size even mid-resize, while the texture still has the previous one.
                NativeBridge.image(texture, width.toFloat(), height.toFloat())
                surface.hovered = NativeBridge.isItemHovered()
            }
            NativeBridge.end()
        } finally {
            NativeBridge.popStyleColor(1)
            NativeBridge.popStyleVar(3)
        }
    }

    private val WINDOW_FLAGS = (WindowFlags.NoDecoration + WindowFlags.NoMove + WindowFlags.NoBackground +
        WindowFlags.NoSavedSettings + WindowFlags.NoScrollWithMouse).value
}
