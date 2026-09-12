package com.projectx.game.input.action

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.toFunctionHandle
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OInput
import com.projectx.game.platform.Platform
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * Windows reports the OS's own hardware-vs-injected verdict for every mouse event in a second packet that
 * the Linux client registers but never sends: a low-level mouse hook feeds the OS flags into
 * `EventListeners::DispatchMouseEventWithSource`, a listener records them into the source ring, and
 * `SendNativeMouseClick` drains that ring alongside the click event.
 *
 * Driving only the game-action path would emit a click with no source report at all — a louder signal
 * than a wrong flag. So mirror what the OS hook does for real input, with the injected bit clear, by
 * calling the client's own dispatcher rather than installing or faking anything at the OS layer.
 */
internal object InputSourceReport {
    private const val SOURCE_HARDWARE = 0

    const val WM_MOUSEMOVE = 0x200
    const val WM_LBUTTONDOWN = 0x201
    const val WM_LBUTTONUP = 0x202
    const val WM_RBUTTONDOWN = 0x204
    const val WM_RBUTTONUP = 0x205
    const val WM_MBUTTONDOWN = 0x207
    const val WM_MBUTTONUP = 0x208

    /** Absent on Linux, where neither the function nor the packet exists — resolution simply yields null. */
    private val dispatchMouseEventWithSource: MethodHandle? by lazy {
        if (Platform.current != Platform.WINDOWS) null
        else runCatching {
            NativeAccess.BASE_ADDR.asSlice(OFunctions.EVENTLISTENERS_DISPATCHMOUSEEVENTWITHSOURCE, 8)
                .toFunctionHandle(
                    FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
                )
        }.getOrNull()
    }

    val isAvailable: Boolean get() = dispatchMouseEventWithSource != null

    fun report(message: Int, x: Int, y: Int) {
        val dispatch = dispatchMouseEventWithSource ?: return
        val handler = runCatching { Bootstrap.client.input.get(JAVA_LONG, OInput.GLOBAL_HANDLER) }.getOrDefault(0L)
        if (handler == 0L) return
        // The OS hook offers each named handler the event before falling back to the global one; only the
        // global handler owns the source ring, so that is the one worth driving.
        runCatching { dispatch.invoke(handler, x, y, message, SOURCE_HARDWARE) }
    }
}
