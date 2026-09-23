package com.projectx.ui.compose.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import com.projectx.ui.backend.dsl.commands.ImGuiCommand
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiStyleVar
import com.projectx.ui.backend.dsl.utils.ModernTheme.withModernTheme
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.CommandRenderer
import com.projectx.script.ComposePanel
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import com.projectx.ui.compose.ComposeSurface
import kotlin.math.ceil
import kotlin.math.floor

/** A running script's own Compose panel. */
data class ScriptPanel(val key: String, val title: String, val panel: ComposePanel)

/** What the script cards and world layer show, published on the render thread for the two surfaces to draw. */
object HudState {
    var windows by mutableStateOf(emptyList<HudWindow>())
    var world by mutableStateOf(emptyList<Shape>())
    var panels by mutableStateOf(emptyList<ScriptPanel>())

    /** The latest frame's callbacks; nodes find theirs by key. */
    @Volatile var actions = HudActions()

    val positions = mutableStateMapOf<String, Offset>()
    val collapsed = mutableStateMapOf<String, Boolean>()
    val hints = mutableStateMapOf<String, String>()

    /** Panels taken down after throwing, by key, so one broken script cannot keep breaking the others. */
    val failed = mutableSetOf<String>()

    /** The panel being composed right now - which one to blame if composing throws. */
    @Volatile var composing: String? = null

    /** Where each card was laid out last frame, in display coordinates - what the presenter cuts out and hit-tests. */
    val cards = mutableMapOf<String, Rect>()
}

/**
 * Turns each frame's recorded commands into Compose: script windows become cards on one surface, world drawing goes
 * onto another behind everything, and only invisible engine hosts still run as ImGui windows.
 *
 * The world surface is click-through - it is drawn into ImGui's background list, below every window. Each card is
 * shown by its own ImGui window displaying just that card's part of the cards texture, which is what gives cards the
 * right stacking against the main panel and lets them take clicks without the rest of the screen doing so.
 */
object HudHost {
    private const val PRIMARY = 0

    private val world = ComposeSurface("world") { WorldLayer() }
    private val panels = ComposeSurface("cards") { HudCards() }

    val hovered: Boolean get() = panels.hovered

    fun update(commands: List<ImGuiCommand>) {
        val actions = HudActions()
        val frame = HudParser(commands, actions).parse()
        HudState.actions = actions
        if (frame.windows != HudState.windows) HudState.windows = frame.windows
        if (frame.world != HudState.world) HudState.world = frame.world
        val panels = ScriptExecutor.activeScripts.mapNotNull { script ->
            val panel = script as? ComposePanel ?: return@mapNotNull null
            val key = "compose:${script.javaClass.name}"
            if (key in HudState.failed) return@mapNotNull null
            val title = runCatching { panel.panelTitle }.getOrNull()
                ?: script.javaClass.getAnnotation(ScriptDescription::class.java)?.name
                ?: script.javaClass.simpleName
            ScriptPanel(key, title, panel)
        }
        if (panels != HudState.panels) HudState.panels = panels
        val present = frame.windows.mapTo(HashSet()) { it.key } + panels.map { it.key }
        HudState.cards.keys.retainAll(present)
        if (frame.native.isNotEmpty()) withModernTheme { CommandRenderer.executePrecomputed(frame.native) }
    }

    fun drawFrame(wheel: Float) {
        val (displayWidth, displayHeight) = NativeBridge.getDisplaySize()
        val width = displayWidth.toInt()
        val height = displayHeight.toInt()
        if (width <= 0 || height <= 0) return
        val (mx, my) = NativeBridge.getMousePos()
        val mouse = Offset(mx, my)
        val down = NativeBridge.isMouseDown(PRIMARY)

        val shapes = HudState.world
        if (shapes.isEmpty()) {
            world.release()
        } else {
            val crop = bounds(shapes, width, height)
            world.render(width, height, mouse, down = false, wheel = 0f, crop = crop)?.let { texture ->
                val area = world.region
                NativeBridge.getBackgroundDrawList()?.let {
                    NativeBridge.drawListAddImage(it, texture, area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat())
                }
            }
        }

        if (HudState.windows.isEmpty() && HudState.panels.isEmpty()) {
            panels.release()
            return
        }
        val cards = HudState.cards.values
        // Until the cards have been laid out once there is nothing to crop to, so the first render reads it all.
        val crop = if (cards.isEmpty()) IntRect(0, 0, width, height) else snap(
            cards.minOf { it.left }, cards.minOf { it.top }, cards.maxOf { it.right }, cards.maxOf { it.bottom }, width, height,
        )
        val texture = try {
            panels.render(width, height, mouse, down, wheel, crop)
        } catch (t: Throwable) {
            takeDownFailedPanel(t)
            return
        } ?: return
        val area = panels.region
        var anyHovered = false
        HudState.cards.forEach { (key, rect) ->
            if (rect.width <= 0f || rect.height <= 0f) return@forEach
            NativeBridge.setNextWindowPos(rect.left, rect.top)
            NativeBridge.setNextWindowSize(rect.width, rect.height)
            NativeBridge.pushStyleVarVec2(ImGuiStyleVar.WindowPadding, 0f, 0f)
            NativeBridge.pushStyleVarFloat(ImGuiStyleVar.WindowBorderSize, 0f)
            NativeBridge.pushStyleColor(ImGuiCol.WindowBg, 0)
            try {
                if (NativeBridge.begin("##card-$key", null, CARD_FLAGS)) {
                    NativeBridge.image(
                        texture, rect.width, rect.height,
                        (rect.left - area.left) / area.width, (rect.top - area.top) / area.height,
                        (rect.right - area.left) / area.width, (rect.bottom - area.top) / area.height,
                    )
                    if (NativeBridge.isItemHovered()) anyHovered = true
                }
                NativeBridge.end()
            } finally {
                NativeBridge.popStyleColor(1)
                NativeBridge.popStyleVar(2)
            }
        }
        panels.hovered = anyHovered
    }

    /**
     * A script's Compose panel threw while being drawn. The card surface is rebuilt without it; if the throw came
     * from outside any one panel, every script panel is taken down, since there is no telling which one caused it.
     */
    private fun takeDownFailedPanel(t: Throwable) {
        val culprit = HudState.composing
        HudState.composing = null
        if (culprit != null) {
            HudState.failed += culprit
            println("[Overlay] ${culprit.removePrefix("compose:")}'s panel threw and was taken down: ${t::class.simpleName}: ${t.message}")
        } else {
            HudState.panels.forEach { HudState.failed += it.key }
            println("[Overlay] Script panels were taken down after drawing threw: ${t::class.simpleName}: ${t.message}")
        }
        t.printStackTrace()
        HudState.panels = HudState.panels.filter { it.key !in HudState.failed }
        panels.dispose()
    }

    /**
     * The area the world drawing covers, padded for text and strokes and snapped outward to a coarse grid, so the
     * texture keeps its size while things move a little and is only rebuilt when the drawing spreads or shrinks.
     */
    private fun bounds(shapes: List<Shape>, width: Int, height: Int): IntRect {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        fun include(x: Float, y: Float) {
            if (x < left) left = x
            if (y < top) top = y
            if (x > right) right = x
            if (y > bottom) bottom = y
        }
        shapes.forEach { shape ->
            when (shape) {
                is Shape.Line -> { include(shape.x1, shape.y1); include(shape.x2, shape.y2) }
                is Shape.Rect -> { include(shape.x1, shape.y1); include(shape.x2, shape.y2) }
                is Shape.Circle -> { include(shape.x - shape.radius, shape.y - shape.radius); include(shape.x + shape.radius, shape.y + shape.radius) }
                is Shape.Text -> { include(shape.x, shape.y); include(shape.x + shape.text.length * TEXT_ADVANCE, shape.y + TEXT_HEIGHT) }
                is Shape.Image -> { include(shape.x1, shape.y1); include(shape.x2, shape.y2) }
                is Shape.Poly -> shape.points.chunked(2).forEach { if (it.size == 2) include(it[0], it[1]) }
            }
        }
        if (left > right || top > bottom) return IntRect(0, 0, 1, 1)
        return snap(left - PADDING, top - PADDING, right + PADDING, bottom + PADDING, width, height)
    }

    private fun snap(left: Float, top: Float, right: Float, bottom: Float, width: Int, height: Int): IntRect {
        val l = (floor(left / GRID).toInt() * GRID).coerceIn(0, width - 1)
        val t = (floor(top / GRID).toInt() * GRID).coerceIn(0, height - 1)
        val r = (ceil(right / GRID).toInt() * GRID).coerceIn(l + 1, width)
        val b = (ceil(bottom / GRID).toInt() * GRID).coerceIn(t + 1, height)
        return IntRect(l, t, r, b)
    }

    fun dispose() {
        world.dispose()
        panels.dispose()
    }

    private const val GRID = 128
    private const val PADDING = 8f
    private const val TEXT_ADVANCE = 8f
    private const val TEXT_HEIGHT = 20f

    private val CARD_FLAGS = (WindowFlags.NoDecoration + WindowFlags.NoMove + WindowFlags.NoBackground +
        WindowFlags.NoSavedSettings + WindowFlags.NoScrollWithMouse + WindowFlags.NoFocusOnAppearing).value
}
