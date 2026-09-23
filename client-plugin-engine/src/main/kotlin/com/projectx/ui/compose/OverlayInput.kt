package com.projectx.ui.compose

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.projectx.game.input.Key
import com.projectx.game.memory.NativeAccess
import com.projectx.ui.backend.dsl.ImGuiState
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.invoke.MethodHandle

object OverlayInput {
    /**
     * The wheel is only readable through ImGui once the mouse is over the overlay, since the overlay's window
     * procedure keeps it from the game. Bootstraps built before the export existed simply have no wheel.
     */
    private val getMouseWheel: MethodHandle? by lazy {
        runCatching { NativeAccess.getFunction("ProjectX_ImGui_GetMouseWheel") { FunctionDescriptor.of(JAVA_FLOAT) } }
            .onFailure { println("[Overlay] This bootstrap has no mouse wheel export; scroll by dragging the scrollbar") }
            .getOrNull()
    }

    fun mouseWheel(): Float = getMouseWheel?.let { it.invokeExact() as Float } ?: 0f
}

/**
 * Coarse time for the interface, advanced by the render loop. Timers and the caret read this rather than asking
 * for every frame, so an idle panel is not re-rendered sixty times a second just to move a clock.
 */
object OverlayClock {
    private const val CARET_PERIOD_MS = 530

    var nowSeconds by mutableLongStateOf(System.currentTimeMillis() / 1000)
        private set

    var caretOn by mutableStateOf(true)
        private set

    fun tick() {
        val millis = System.currentTimeMillis()
        if (millis / 1000 != nowSeconds) nowSeconds = millis / 1000
        val caret = (millis / CARET_PERIOD_MS) % 2 == 0L
        if (caret != caretOn) caretOn = caret
    }
}

/**
 * A single line of text typed into the overlay.
 *
 * Reads and writes through [read] and [write], so a field can edit a [Setting] directly rather than a copy that
 * has to be kept in step. [accepts] limits which characters it takes; [onDone] runs when typing ends.
 */
class OverlayText(
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val maxLength: Int = 64,
    private val accepts: (Char) -> Boolean = { true },
    val multiline: Boolean = false,
    val onFocus: () -> Unit = {},
    val onDone: () -> Unit = {},
) {
    constructor(initial: String = "", maxLength: Int = 64) : this(mutableStateOf(initial), maxLength)

    private constructor(state: MutableState<String>, maxLength: Int) :
        this({ state.value }, { state.value = it }, maxLength)

    var text: String
        get() = read()
        set(value) = write(value)

    fun type(char: Char) {
        if (text.length < maxLength && accepts(char)) text += char
    }

    fun backspace() {
        text = text.dropLast(1)
    }

    companion object {
        fun of(setting: ImGuiState<String>, maxLength: Int = 128) =
            OverlayText({ setting.value }, { setting.value = it }, maxLength)
    }
}

/**
 * Keyboard focus for the overlay's text fields.
 *
 * Keys arrive through the game's own input hooks rather than through ImGui, and a focused field holds them
 * back from the game - otherwise typing a search would also type it into the chatbox.
 */
object OverlayKeyboard {
    var focused: OverlayText? by mutableStateOf(null)
        private set

    private var focusedBounds: Rect = Rect.Zero
    private var focusedSurface = ""

    fun focus(field: OverlayText, bounds: Rect, surface: String) {
        if (focused === field) return
        blur()
        field.onFocus()
        focused = field
        focusedBounds = bounds
        focusedSurface = surface
    }

    fun updateBounds(field: OverlayText, bounds: Rect) {
        if (focused === field) focusedBounds = bounds
    }

    fun blur() {
        val field = focused ?: return
        focused = null
        field.onDone()
    }

    /** A press at [x], [y] on [surface] ends typing unless it landed on the focused field itself. */
    fun blurUnlessInside(x: Float, y: Float, surface: String) {
        if (focused != null && (surface != focusedSurface || !focusedBounds.contains(Offset(x, y)))) blur()
    }

    /** Returns true when a focused field took the key, so the game must not see it. */
    fun onKeyDown(keyCode: Int): Boolean {
        val field = focused ?: return false
        when (keyCode) {
            Key.BACKSPACE.native -> field.backspace()
            Key.RETURN.native, Key.KP_ENTER.native -> if (field.multiline) field.type('\n') else blur()
            Key.ESCAPE.native, Key.TAB.native -> blur()
        }
        return true
    }

    fun onKeyUp(): Boolean = focused != null

    fun onChar(charCode: Int): Boolean {
        val field = focused ?: return false
        val char = charCode.toChar()
        if (!char.isISOControl()) field.type(char)
        return true
    }
}
