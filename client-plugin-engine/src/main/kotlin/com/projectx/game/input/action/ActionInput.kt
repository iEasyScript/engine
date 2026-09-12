package com.projectx.game.input.action

import com.projectx.game.hooks.HookManager
import com.projectx.game.hooks.impl.OnKeyDown
import com.projectx.game.hooks.impl.OnKeyUp
import com.projectx.game.hooks.impl.OnLeftButtonDown
import com.projectx.game.hooks.impl.OnLeftButtonUp
import com.projectx.game.hooks.impl.OnMouseMotion
import com.projectx.game.hooks.impl.OnScrollWheel
import com.projectx.game.input.InputArbiter
import com.projectx.game.input.InputHandle
import com.projectx.game.input.SyntheticButtonState
import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.NativeAccess.toFunctionHandle
import com.projectx.game.memory.NativeAccess.toMemorySegment
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OInput
import com.projectx.game.nxt.OInputGlobals
import com.projectx.game.nxt.OInputHandler
import com.projectx.game.nxt.OInputState
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * Input that really happens: the cursor moves, raycasts run, `DoAction` fires, and the client's own tick
 * orchestrator produces whatever packets that generates. This is the path for script actions and for
 * staying logged in — the opposite of `com.projectx.game.input.wire`, which only tells the server where
 * the cursor was and must never reach any of this.
 *
 * Everything goes through the trampolines (the original functions) rather than the hooked addresses, so an
 * injected event is not re-observed by our own hooks — which would both pollute the recorder and let the UI
 * toggle key swallow the injection instead of passing it to the game.
 *
 * `internal` so scripts cannot reach these directly; they get the curated overloads in `script.api`.
 */
internal object ActionInput {
    private const val KEY_NODE_SIZE = 0x28L

    private val callListenersFn: MethodHandle by lazy {
        NativeAccess.BASE_ADDR.asSlice(OFunctions.EVENTLISTENERS_CALLLISTENERS_FF, 8)
            .toFunctionHandle(FunctionDescriptor.of(JAVA_BYTE, JAVA_LONG, JAVA_LONG))
    }

    fun moveMouse(x: Int, y: Int) {
        val input = InputHandle.addressOrZero()
        if (input == 0L) return
        InputArbiter.recordActionInput()
        HookManager.trampoline(OnMouseMotion::onMouseMotionHook.name)
            .invokeExact(input, x.toFloat(), y.toFloat())
        InputSourceReport.report(InputSourceReport.WM_MOUSEMOVE, x, y)
    }

    fun leftClickDown(x: Int, y: Int) {
        val input = InputHandle.addressOrZero()
        if (input == 0L) return
        InputArbiter.recordActionInput()
        HookManager.trampoline(OnLeftButtonDown::onLeftButtonDownHook.name)
            .invokeExact(input, x.toFloat(), y.toFloat())
        InputSourceReport.report(InputSourceReport.WM_LBUTTONDOWN, x, y)
    }

    fun leftClickUp(x: Int, y: Int) {
        val input = InputHandle.addressOrZero()
        if (input == 0L) return
        InputArbiter.recordActionInput()
        HookManager.trampoline(OnLeftButtonUp::onLeftButtonUpHook.name)
            .invokeExact(input, x.toFloat(), y.toFloat())
        InputSourceReport.report(InputSourceReport.WM_LBUTTONUP, x, y)
    }

    fun leftClick(x: Int, y: Int) {
        leftClickDown(x, y)
        leftClickUp(x, y)
    }

    /**
     * The position handed to wheel listeners is read from `Input.lastMouseX/Y`, so the move has to happen
     * first or the event carries a stale position.
     */
    fun scroll(x: Int, y: Int, delta: Int) {
        val input = InputHandle.addressOrZero()
        if (input == 0L) return
        moveMouse(x, y)
        HookManager.trampoline(OnScrollWheel::onScrollWheelHook.name)
            .invokeExact(input, 0f, delta.toFloat())
    }

    fun rightClickDown(x: Int, y: Int) =
        dispatchButton(OInputHandler.RMOUSE_DOWN, OInputGlobals.RIGHT_BUTTON_STATE, true, x, y, InputSourceReport.WM_RBUTTONDOWN)

    fun rightClickUp(x: Int, y: Int) =
        dispatchButton(OInputHandler.RMOUSE_UP, OInputGlobals.RIGHT_BUTTON_STATE, false, x, y, InputSourceReport.WM_RBUTTONUP)

    fun rightClick(x: Int, y: Int) {
        rightClickDown(x, y)
        rightClickUp(x, y)
    }

    fun middleClickDown(x: Int, y: Int) =
        dispatchButton(OInputHandler.MMOUSE_DOWN, OInputGlobals.MIDDLE_BUTTON_STATE, true, x, y, InputSourceReport.WM_MBUTTONDOWN)

    fun middleClickUp(x: Int, y: Int) =
        dispatchButton(OInputHandler.MMOUSE_UP, OInputGlobals.MIDDLE_BUTTON_STATE, false, x, y, InputSourceReport.WM_MBUTTONUP)

    fun middleClick(x: Int, y: Int) {
        middleClickDown(x, y)
        middleClickUp(x, y)
    }

    // invokeExact links the call site from the argument types AND the expected return type, and it demands
    // an exact match. Wrapping it in a value-producing lambda (runCatching) makes Kotlin infer Any, so the
    // site links as (MemorySegment,int)Object while the trampoline is (MemorySegment,int)void —
    // WrongMethodTypeException on every injected key. It must stay in statement position.
    fun keyDown(key: Int) {
        val input = InputHandle.segmentOrNull() ?: return
        try {
            HookManager.trampoline(OnKeyDown::onKeyDownHook.name).invokeExact(input, key)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun keyUp(key: Int) {
        val input = InputHandle.segmentOrNull() ?: return
        try {
            HookManager.trampoline(OnKeyUp::onKeyUpHook.name).invokeExact(input, key)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    /**
     * Walks the client's key-down tree instead of calling its own lookup: MSVC inlines that lookup at every
     * call site, so on Windows there is no function to call and the previous downcall silently answered
     * `false` for every key. The tree is a lower-bound descent keyed on the platform key code.
     */
    fun isKeyDown(key: Int): Boolean = runCatching {
        var node = NativeAccess.BASE_ADDR.readLong(OInputState.KEY_DOWN_MAP_ROOT)
        var candidate = 0L
        while (node != 0L) {
            val entry = node.toMemorySegment(KEY_NODE_SIZE)
            if (entry.readInt(OInputState.KEY_NODE_VK) < key) {
                node = entry.readLong(OInputState.KEY_NODE_RIGHT)
            } else {
                candidate = node
                node = entry.readLong(OInputState.KEY_NODE_LEFT)
            }
        }
        if (candidate == 0L) return@runCatching false
        val found = candidate.toMemorySegment(KEY_NODE_SIZE)
        found.readInt(OInputState.KEY_NODE_VK) == key && found.readByte(OInputState.KEY_NODE_PRESSED) != 0.toByte()
    }.getOrDefault(false)

    /**
     * Middle and right buttons have no Input entry point of their own. This reproduces exactly what the
     * platform event pump does: write the button-state byte and the cursor position into InputState, then
     * run the matching listener slot on the global handler.
     */
    private fun dispatchButton(slot: Long, stateOffset: Long, pressed: Boolean, x: Int, y: Int, message: Int) {
        try {
            val handler = InputHandle.segmentOrNull()?.get(JAVA_LONG, OInput.GLOBAL_HANDLER) ?: return
            if (handler == 0L) return
            InputArbiter.recordActionInput()
            val base = NativeAccess.BASE_ADDR
            SyntheticButtonState.markWritten(stateOffset)
            base.set(JAVA_BYTE, stateOffset, if (pressed) 1 else 0)
            base.set(JAVA_FLOAT, OInputGlobals.MOUSE_X, x.toFloat())
            base.set(JAVA_FLOAT, OInputGlobals.MOUSE_Y, y.toFloat())
            callListenersFn.invoke(packXY(x, y), handler + slot)
            InputSourceReport.report(message, x, y)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /** CallListeners receives both floats in one integer register: low dword x, high dword y. */
    private fun packXY(x: Int, y: Int): Long =
        (x.toFloat().toRawBits().toLong() and 0xFFFFFFFFL) or (y.toFloat().toRawBits().toLong() shl 32)
}
