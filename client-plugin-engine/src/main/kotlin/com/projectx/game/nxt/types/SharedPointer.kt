package com.projectx.game.nxt.types

import com.projectx.game.memory.NativeAccess.readLong
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

class SharedPointer(val ptr : MemorySegment) {
    val isEmpty: Boolean get() = this.value(0L).address() == 0L

    fun value(size: Long) : MemorySegment {
        return ptr.get(ValueLayout.ADDRESS, 0x8).reinterpret(size)
    }

    fun valueOrNull(size: Long) : MemorySegment? {
        if (ptr.readLong(0x8) == 0L) return null
        return ptr.get(ValueLayout.ADDRESS, 0x8).reinterpret(size)
    }
}