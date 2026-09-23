package com.projectx.ui.highlight

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.math.Vector2f
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.interfaces.ComponentVisibility
import com.projectx.game.nxt.interfaces.InspectedComponent
import com.projectx.game.nxt.interfaces.InterfaceInspector
import com.projectx.game.nxt.interfaces.InterfacePick
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.ui.UIState
import com.projectx.ui.compose.ComposeOverlay
import com.projectx.ui.compose.OverlayNavigation
import com.projectx.ui.compose.Page
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGUIRender
import world.gregs.voidps.gameval.Gameval

/**
 * Draws the inspector's highlight over the game's own interfaces: the component the cursor is over while
 * inspect mode is armed, and the one currently selected in the tab.
 */
object InterfaceDebugHighlightRenderer {
    private const val LABEL_PAD = 4f
    private const val TICK = 6f

    // Deliberately outside the game's palette. RS3's interfaces are brown and gold, so an accent-coloured
    // highlight sits on top of gold trim and disappears; cyan and magenta appear nowhere in the client UI.
    private val SELECTED = 0xFF00E5FF.toInt()
    private val HOVER = 0xFFFF3DFF.toInt()
    private val CONTAINER = 0x6600E5FF
    private val PLATE = 0xF00A0A0A.toInt()

    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        if (!ComposeOverlay.visible || OverlayNavigation.page != Page.Interfaces) {
            InterfacePick.cancel()
            return
        }
        if (runCatching { Bootstrap.client.mainState }.getOrNull() != MainState.LOGGED_IN) return

        val hover = if (InterfacePick.active) trackCursor() else null.also { InterfacePick.hover = null }
        val selected = selectedComponent()
        val container = containerOfSelection()

        if (hover == null && selected == null && !InterfacePick.active) return

        backgroundDrawList {
            // Container first and unlabelled, so a selected slot reads as sitting inside something rather
            // than floating on its own - that containment is the thing drilling down is trying to show.
            container?.rect?.let { outline(it, CONTAINER) }
            selected?.rect?.let { draw(it, SELECTED, label(selected), dim = hover != null) }
            hover?.rect?.let { draw(it, HOVER, label(hover), dim = false) }
            if (InterfacePick.active) drawPickHint(hover)
        }
    }

    /** The component a selected slot child belongs to, or null when the selection is not a slot child. */
    private fun containerOfSelection(): InspectedComponent? {
        if (UIState.interfaceDebugSlotIndex.value < 0) return null
        val interfaceId = UIState.interfaceDebugInterfaceId.value
        if (interfaceId < 0) return null
        val componentId = UIState.interfaceDebugComponentId.value.coerceAtLeast(0)
        return runCatching { InterfaceInspector.component(interfaceId, componentId) }.getOrNull()
    }

    private fun trackCursor(): InspectedComponent? {
        val (x, y) = runCatching { NativeBridge.getMousePos() }.getOrNull() ?: return null
        val hit = runCatching { InterfaceInspector.hitTest(x, y) }.getOrNull()
        InterfacePick.hover = hit
        return hit
    }

    /**
     * Follows the slot index as well as the ids.
     *
     * Drilling into a slot child is the point at which the highlight matters most - the container covers the
     * whole row of tabs or inventory squares, so outlining it says nothing about which one is selected.
     */
    private fun selectedComponent(): InspectedComponent? {
        val interfaceId = UIState.interfaceDebugInterfaceId.value
        if (interfaceId < 0) return null
        val componentId = UIState.interfaceDebugComponentId.value.coerceAtLeast(0)
        val slotIndex = UIState.interfaceDebugSlotIndex.value
        return runCatching {
            if (slotIndex >= 0) InterfaceInspector.slotChild(interfaceId, componentId, slotIndex)
            else InterfaceInspector.component(interfaceId, componentId)
        }.getOrNull()
    }

    private fun label(target: InspectedComponent): String {
        val name = runCatching { Gameval.componentLabel(target.interfaceId, target.componentId) }
            .getOrDefault("${target.interfaceId}:${target.componentId}")
        val state = when (target.visibility) {
            ComponentVisibility.DRAWN -> ""
            ComponentVisibility.LAID_OUT -> "  (laid out, not drawn)"
            ComponentVisibility.HIDDEN -> "  (hidden)"
        }
        return "$name$state"
    }

    /**
     * Outline plus corner ticks rather than a filled box: the fill washes out whatever is being inspected,
     * and the ticks stay readable where an edge runs along another component's border.
     */
    private fun BackgroundDrawListScope.draw(rect: ScreenRect, color: Int, text: String, dim: Boolean) {
        val alpha = if (dim) 0x66 else 0xFF
        val outline = (color and 0x00FFFFFF) or (alpha shl 24)
        val wash = (color and 0x00FFFFFF) or ((if (dim) 0x10 else 0x1F) shl 24)

        val left = rect.x.toFloat()
        val top = rect.y.toFloat()
        val right = (rect.x + rect.width).toFloat()
        val bottom = (rect.y + rect.height).toFloat()

        rectFilled(Vector2f(left, top), Vector2f(right, bottom), wash, rounding = 2f)
        rect(Vector2f(left, top), Vector2f(right, bottom), outline, rounding = 2f, thickness = 1f)
        corners(left, top, right, bottom, outline)
        if (!dim) plate(left, top, bottom, text, outline)
    }

    /** Bare outline, no fill or label - context rather than a second thing competing for attention. */
    private fun BackgroundDrawListScope.outline(rect: ScreenRect, color: Int) {
        rect(
            Vector2f(rect.x.toFloat(), rect.y.toFloat()),
            Vector2f((rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat()),
            color,
            rounding = 2f,
            thickness = 1f,
        )
    }

    private fun BackgroundDrawListScope.corners(l: Float, t: Float, r: Float, b: Float, color: Int) {
        val arm = minOf(TICK, (r - l) / 3f, (b - t) / 3f).coerceAtLeast(1f)
        line(Vector2f(l, t), Vector2f(l + arm, t), color, 2f)
        line(Vector2f(l, t), Vector2f(l, t + arm), color, 2f)
        line(Vector2f(r, t), Vector2f(r - arm, t), color, 2f)
        line(Vector2f(r, t), Vector2f(r, t + arm), color, 2f)
        line(Vector2f(l, b), Vector2f(l + arm, b), color, 2f)
        line(Vector2f(l, b), Vector2f(l, b - arm), color, 2f)
        line(Vector2f(r, b), Vector2f(r - arm, b), color, 2f)
        line(Vector2f(r, b), Vector2f(r, b - arm), color, 2f)
    }

    /**
     * The label sits above the component, flipping below when that would leave the screen and clamping
     * horizontally. It is drawn on an opaque plate — over a busy interface, bare text lands on top of the
     * game's own labels and neither is readable.
     */
    private fun BackgroundDrawListScope.plate(left: Float, top: Float, bottom: Float, text: String, color: Int) {
        val (screenW, screenH) = runCatching { NativeBridge.getDisplaySize() }.getOrDefault(1920f to 1080f)
        // Measured, not estimated: the font is not fixed-width, so a per-character guess leaves long labels
        // like "debuff_bar:buff_render_layer" running past the plate they are supposed to sit on.
        val (textW, textH) = runCatching { NativeBridge.calcTextSize(text) }.getOrDefault(0f to 0f)
        val width = textW + LABEL_PAD * 2f
        val height = textH + LABEL_PAD

        val above = top - height - 2f
        val y = if (above >= 0f) above else (bottom + 2f).coerceAtMost(screenH - height)
        val x = left.coerceIn(0f, (screenW - width).coerceAtLeast(0f))

        rectFilled(Vector2f(x, y), Vector2f(x + width, y + height), PLATE, rounding = 2f)
        rect(Vector2f(x, y), Vector2f(x + width, y + height), color, rounding = 2f, thickness = 1f)
        text(Vector2f(x + LABEL_PAD, y + LABEL_PAD / 2f), color, text)
    }

    private fun BackgroundDrawListScope.drawPickHint(hover: InspectedComponent?) {
        val (x, y) = runCatching { NativeBridge.getMousePos() }.getOrDefault(0f to 0f)
        val message =
            if (hover == null) "Inspect: no component here  -  click to cancel"
            else "Inspect: click to select  -  Esc to cancel"
        val (textW, textH) = runCatching { NativeBridge.calcTextSize(message) }.getOrDefault(0f to 0f)
        val width = textW + LABEL_PAD * 2f
        val height = textH + LABEL_PAD
        val top = y + 18f
        rectFilled(Vector2f(x + 12f, top), Vector2f(x + 12f + width, top + height), PLATE, rounding = 2f)
        text(Vector2f(x + 12f + LABEL_PAD, top + LABEL_PAD / 2f), HOVER, message)
    }
}
