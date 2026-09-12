package com.projectx.game.memory.eastl

import com.projectx.game.memory.NativeAccess.readByte
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Reader for an embedded EASTL `basic_string<char>`. In SSO mode the size byte holds
 * `SSO_CAPACITY - size`, so a full 23-char inline string stores 0 there (doubling as the
 * NUL terminator) - the size must be derived, not read directly, or it looks empty.
 */
class EastlString(private val ptr: MemorySegment) : CharSequence {

    private val sizeByte: Int
        get() = ptr.readByte(EastlOffsets.SIZE).toInt() and 0xFF

    fun isHeap(): Boolean = sizeByte and 0x80 != 0
    fun isSSO(): Boolean = !isHeap()

    override val length: Int
        get() = if (isHeap()) heapLength() else EastlOffsets.SSO_CAPACITY - sizeByte

    /**
     * Zero when the stored length is not plausible for a string.
     *
     * The heap size is a raw 64-bit read, so on memory that is not actually a string it is arbitrary — and
     * the caller then reinterprets a span that long and scans it for a terminator. That walk faults rather
     * than throwing, taking the client with it, so an implausible length has to be rejected here.
     */
    private fun heapLength(): Int {
        val stored = ptr.get(ValueLayout.JAVA_LONG, EastlOffsets.HEAP_SIZE)
        return if (stored < 0L || stored > EastlOffsets.MAX_PLAUSIBLE_LENGTH) 0 else stored.toInt()
    }

    override fun get(index: Int): Char {
        val data = if (isHeap()) ptr.get(ValueLayout.ADDRESS, 0).reinterpret((length + 1).toLong()) else ptr
        return data.get(ValueLayout.JAVA_BYTE, index.toLong()).toInt().toChar()
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        toString().subSequence(startIndex, endIndex)

    override fun toString(): String {
        val len = length
        if (len <= 0) return ""
        val data = if (isHeap()) ptr.get(ValueLayout.ADDRESS, 0).reinterpret((len + 1).toLong()) else ptr
        return data.getString(0)
    }

    object EastlOffsets {
        const val SIZE = 0x17L
        const val HEAP_SIZE = 0x08L
        const val SSO_CAPACITY = 23

        /** Interface labels, chat lines and item names all sit far below this; noise sits far above. */
        const val MAX_PLAUSIBLE_LENGTH = 64L * 1024L
    }
}
