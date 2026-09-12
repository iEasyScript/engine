package com.projectx.game.hooks.impl

import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.input.wire.KeyPressSender
import java.lang.foreign.MemorySegment

/**
 * The client's per-tick prot pump, hooked purely to give the key path a window.
 *
 * The keyboard packet is built inline in this function from a vector on the per-tick input-event view, so
 * there is no callee to drive on its own. Wrapping the call is what lets [KeyPressSender] point that vector at
 * engine-owned records for exactly the duration of one flush and put it back afterwards — the client's own
 * encoder produces every byte, and nothing outside this call ever observes the substitution.
 */
object FlushPerTickCapture {
    // invokeExact links the call site from the argument types AND the expected return type. Anything that
    // makes it a value-producing expression — a generic wrapper, runCatching, being a block's last expression —
    // links the site as returning Object against a void trampoline and throws WrongMethodTypeException. An
    // exception escaping an upcall stub terminates the VM, so it must stay in statement position and every
    // other line here must be incapable of throwing out of the hook.
    @JvmStatic
    @Hook("CLIENTPROT_FLUSHPERTICK")
    fun flushPerTickHook(clientProt: MemorySegment) {
        val swapped = try {
            KeyPressSender.beginSwap()
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
        try {
            HookManager.trampoline(::flushPerTickHook.name).invokeExact(clientProt)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        if (swapped) {
            try {
                KeyPressSender.endSwap()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}
