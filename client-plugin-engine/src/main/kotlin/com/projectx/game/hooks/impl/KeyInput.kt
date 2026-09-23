package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.hooks.SupportedOn
import com.projectx.game.hooks.SymbolHook
import com.projectx.game.input.InputRecorder
import com.projectx.game.input.Key
import com.projectx.game.input.KeyCharEvent
import com.projectx.game.input.KeyboardEvent
import com.projectx.game.nxt.interfaces.InterfacePick
import com.projectx.game.platform.NativeLibraries
import com.projectx.game.platform.Platform
import com.projectx.ui.UIState
import com.projectx.ui.compose.OverlayKeyboard
import java.lang.foreign.MemorySegment

/**
 * Keyboard hooks. Injection lives in `com.projectx.game.input.action.ActionInput`, which drives the
 * trampolines these wrap so an injected key is not re-observed here - that would both pollute the recorder
 * and let the UI toggle key swallow the injection instead of passing it to the game.
 */

object OnKeyDown {
    @JvmStatic
    @SupportedOn(Platform.LINUX)
    @SymbolHook(symbol = "SDL_GetKeyboardFocus", library = NativeLibraries.WINDOWING)
    fun getKeyboardFocusHook(): MemorySegment {
        return Bootstrap.client.sdlManager.sdlWindow.ptr
    }

    @JvmStatic
    @Hook("INPUT_INPUT_ONKEYDOWNINNER")
    fun onKeyDownHook(input: MemorySegment, keyCode: Int) {
        try {
            val event = KeyboardEvent(
                timestampNanos = System.nanoTime(),
                gameTick = Bootstrap.client.clientCycle,
                keyCode = keyCode,
                pressed = true
            )
            if (InputRecorder.isRecording) InputRecorder.record(event)

            if (keyCode == UIState.uiToggleKey.value) {
                UIState.showMainWindow.value = !UIState.showMainWindow.value
                return
            }

            // Swallowed rather than forwarded: Escape is the game's settings key, so letting it through
            // would open that menu on top of whatever is being inspected.
            if (keyCode == Key.ESCAPE.native && InterfacePick.active) {
                InterfacePick.cancel()
                return
            }

            if (OverlayKeyboard.onKeyDown(keyCode)) return
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        HookManager.trampoline(::onKeyDownHook.name).invokeExact(input, keyCode)
    }
}

object OnKeyUp {
    @JvmStatic
    @Hook("INPUT_INPUT_ONKEYUPINNER")
    fun onKeyUpHook(input: MemorySegment, keyCode: Int) {
        try {
            val event = KeyboardEvent(
                timestampNanos = System.nanoTime(),
                gameTick = Bootstrap.client.clientCycle,
                keyCode = keyCode,
                pressed = false
            )
            if (InputRecorder.isRecording) InputRecorder.record(event)
            if (OverlayKeyboard.onKeyUp()) return
        } catch(e: Throwable) {
            e.printStackTrace()
        }
        HookManager.trampoline(::onKeyUpHook.name).invokeExact(input, keyCode)
    }
}

/**
 * The typed-character path. A keystroke produces both a key event and, if it maps to a character, one of
 * these; only this one knows what was actually typed once the keymap and modifiers have been applied, so
 * without it typed text is invisible in a recording.
 *
 * Signature verified against the binary rather than assumed from the key hooks: `this` in the integer
 * register and a 32-bit character code as the second argument.
 */
object OnKeyChar {
    @JvmStatic
    @Hook("INPUT_INPUT_ONKEYCHARINNER")
    fun onKeyCharHook(input: MemorySegment, charCode: Int) {
        try {
            if (InputRecorder.isRecording) {
                InputRecorder.record(
                    KeyCharEvent(
                        timestampNanos = System.nanoTime(),
                        gameTick = Bootstrap.client.clientCycle,
                        charCode = charCode,
                    )
                )
            }
            if (OverlayKeyboard.onChar(charCode)) return
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        HookManager.trampoline(::onKeyCharHook.name).invokeExact(input, charCode)
    }
}

/** F12 in whichever keycode namespace this host's client actually delivers. */
val defaultUiToggleKey: Int get() = Key.F12.native

/** True when [keyCode] belongs to a namespace this host's client will never emit. */
fun isForeignKeycode(keyCode: Int): Boolean = Key.fromNative(keyCode) == null && keyCode !in 32..126

fun keyCodeName(keyCode: Int): String =
    Key.fromNative(keyCode)?.name
        ?: if (keyCode in 32..126) "'${keyCode.toChar()}'" else "0x${keyCode.toString(16).uppercase()}"
