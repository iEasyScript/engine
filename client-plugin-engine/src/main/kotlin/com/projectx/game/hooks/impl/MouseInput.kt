package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.input.InputHandle
import com.projectx.game.input.InputRecorder
import com.projectx.game.input.MouseButton
import com.projectx.game.input.MouseButtonEvent
import com.projectx.game.input.MouseMotionEvent
import com.projectx.game.input.MouseScrollEvent
import com.projectx.game.nxt.OInput
import com.projectx.game.nxt.interfaces.InterfacePick
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_FLOAT

/**
 * Mouse input hooks - captures mouse motion, button presses, and scroll events. The native functions
 * pass the input manager in an integer register and the coordinates in XMM registers; Panama maps
 * Java Long -> integer register and Float -> XMM, so the hook signature (Long, Float, Float) works.
 *
 * Injection lives in `com.projectx.game.input.action.ActionInput`, which drives the trampolines these
 * hooks wrap so a synthetic event is never re-observed here.
 */

object OnMouseMotion {
    @JvmStatic
    @Hook("INPUT_INPUT_ONMOUSEMOTION")
    fun onMouseMotionHook(inputMgr: Long, x: Float, y: Float) {
        try {
            verifyInputOffset(inputMgr)
            val event = MouseMotionEvent(
                timestampNanos = System.nanoTime(),
                gameTick = Bootstrap.client.clientCycle,
                x = x.toInt(),
                y = y.toInt()
            )
            if (InputRecorder.isRecording) InputRecorder.record(event)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        HookManager.trampoline(::onMouseMotionHook.name).invokeExact(inputMgr, x, y)
    }
}

object OnLeftButtonDown {
    @JvmStatic
    @Hook("INPUT_INPUT_ONLEFTBUTTONDOWN")
    fun onLeftButtonDownHook(inputMgr: Long, x: Float, y: Float) {
        try {
            verifyInputOffset(inputMgr)
            val event = MouseButtonEvent(
                timestampNanos = System.nanoTime(),
                gameTick = Bootstrap.client.clientCycle,
                x = x.toInt(),
                y = y.toInt(),
                button = MouseButton.LEFT,
                pressed = true
            )
            if (InputRecorder.isRecording) InputRecorder.record(event)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        // Swallowed while inspect mode is arming: the click is the pick gesture, and forwarding it would
        // also press whatever component is being inspected.
        if (runCatching { InterfacePick.consumeClick() }.getOrDefault(false)) return
        HookManager.trampoline(::onLeftButtonDownHook.name).invokeExact(inputMgr, x, y)
    }
}

object OnLeftButtonUp {
    @JvmStatic
    @Hook("INPUT_INPUT_ONLEFTBUTTONUP")
    fun onLeftButtonUpHook(inputMgr: Long, x: Float, y: Float) {
        try {
            verifyInputOffset(inputMgr)
            val event = MouseButtonEvent(
                timestampNanos = System.nanoTime(),
                gameTick = Bootstrap.client.clientCycle,
                x = x.toInt(),
                y = y.toInt(),
                button = MouseButton.LEFT,
                pressed = false
            )
            if (InputRecorder.isRecording) InputRecorder.record(event)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        HookManager.trampoline(::onLeftButtonUpHook.name).invokeExact(inputMgr, x, y)
    }
}

object OnScrollWheel {
    @JvmStatic
    @Hook("INPUT_INPUT_ONSCROLLWHEEL")
    fun onScrollWheelHook(inputMgr: Long, wheelX: Float, wheelY: Float) {
        try {
            verifyInputOffset(inputMgr)
            // The wheel event carries no position; the client sources it from Input.lastMouseX/Y,
            // which only OnMouseMotion writes.
            val input = MemorySegment.ofAddress(inputMgr).reinterpret(OInput.SIZE)
            val event = MouseScrollEvent(
                timestampNanos = System.nanoTime(),
                gameTick = Bootstrap.client.clientCycle,
                x = input.get(JAVA_FLOAT, OInput.LAST_MOUSE_X).toInt(),
                y = input.get(JAVA_FLOAT, OInput.LAST_MOUSE_Y).toInt(),
                scrollDelta = wheelY.toInt()
            )
            if (InputRecorder.isRecording) InputRecorder.record(event)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        HookManager.trampoline(::onScrollWheelHook.name).invokeExact(inputMgr, wheelX, wheelY)
    }
}

@Volatile
private var inputOffsetVerified = false

/**
 * The engine derives the Input pointer from OClient.INPUT rather than caching whatever `this` a hook
 * happened to see. The hooks still see the real pointer, so compare them once: a mismatch means the
 * embedded-Input offset moved in a client update, and silently injecting into the wrong address is
 * far worse than a loud complaint.
 */
private fun verifyInputOffset(inputMgr: Long) {
    if (inputOffsetVerified) return
    inputOffsetVerified = true
    val derived = InputHandle.addressOrZero()
    if (derived == inputMgr) println("[MouseInput] Input at 0x${inputMgr.toString(16)} matches OClient.INPUT")
    else println("[MouseInput] OFFSET MISMATCH: hook saw 0x${inputMgr.toString(16)}, OClient.INPUT derives 0x${derived.toString(16)} - OClient.INPUT is stale, injection will corrupt memory")
}
