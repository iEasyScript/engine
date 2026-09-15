package com.projectx.game.hooks

import com.projectx.game.memory.NativeAccess.toFunctionHandle
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.invoke.MethodHandle

/**
 * A function pointer in client memory redirected to an engine upcall stub. The client may rewrite the
 * slot at any time - volk reloads its whole table when the device is recreated - so [rearm] adopts
 * whatever pointer it finds there as the new original instead of assuming the first one lasts.
 */
internal class PointerSlotHook(
    val name: String,
    private val slot: MemorySegment,
    private val stub: MemorySegment,
    private val descriptor: FunctionDescriptor,
) {
    @Volatile
    private var originalAddress: Long = 0L

    @Volatile
    var original: MethodHandle? = null
        private set

    fun install() {
        adopt(slot.get(ADDRESS, 0))
        slot.set(ADDRESS, 0, stub)
    }

    /** True when the slot had been rewritten and was redirected again. */
    fun rearm(): Boolean {
        val current = slot.get(ADDRESS, 0)
        if (current.address() == stub.address() || current.address() == 0L) return false
        adopt(current)
        slot.set(ADDRESS, 0, stub)
        return true
    }

    fun restore() {
        if (slot.get(ADDRESS, 0).address() == stub.address()) {
            slot.set(ADDRESS, 0, MemorySegment.ofAddress(originalAddress))
        }
    }

    private fun adopt(target: MemorySegment) {
        originalAddress = target.address()
        original = target.toFunctionHandle(descriptor)
    }
}
