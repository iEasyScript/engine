package com.projectx.game.memory

import com.projectx.game.platform.PlatformMemory
import java.lang.foreign.AddressLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED

/**
 * Reads native memory of THIS process through [PlatformMemory], which lets the kernel check page
 * mappings and report failure — instead of segfaulting the process the way a direct
 * `MemorySegment.get` does.
 *
 * Use this for any pointer chase where the engine may free the underlying allocation
 * out from under us (entity → render model → mesh → vertex buffer is the canonical case).
 * Returns nullable / boolean failure signals so callers can bail without crashing.
 *
 * Cost: one syscall per read. For bulk struct reads use [snapshotInto] — one syscall
 * per struct instead of per field.
 */
object SafeNativeAccess {

    private const val MIN_PLAUSIBLE_ADDR = 0x1000L
    private const val MAX_SNAPSHOT_BYTES = 16L * 1024 * 1024  // 16 MiB safety cap

    /**
     * Heap-backed [MemorySegment]s from [MemorySegment.ofArray] only support 1-byte
     * alignment, so any `.get(LAYOUT, off)` with a layout demanding >1-byte alignment
     * throws. x86-64 handles unaligned reads natively, so we expose unaligned variants
     * and use them everywhere we read snapshots.
     */
    val ADDRESS_UNALIGNED: AddressLayout = ValueLayout.ADDRESS.withByteAlignment(1)

    /**
     * Copies `[addr, addr+size)` from process memory into a JVM-owned byte array
     * wrapped as a [MemorySegment]. Returns null if any byte of the range is unmapped
     * or otherwise unreadable. The returned segment is safe to dereference with any
     * `.get(layout, offset)` for `offset < size`.
     */
    fun snapshotInto(addr: Long, size: Long): MemorySegment? {
        if (addr < MIN_PLAUSIBLE_ADDR || size <= 0 || size > MAX_SNAPSHOT_BYTES) return null
        val intSize = size.toInt()
        val bytes = ByteArray(intSize)
        if (!PlatformMemory.read(addr, bytes, intSize)) return null
        return MemorySegment.ofArray(bytes)
    }

    fun readByte(addr: Long): Byte? = snapshotInto(addr, 1L)?.get(JAVA_BYTE, 0L)
    fun readShort(addr: Long): Short? = snapshotInto(addr, 2L)?.get(JAVA_SHORT_UNALIGNED, 0L)
    fun readInt(addr: Long): Int? = snapshotInto(addr, 4L)?.get(JAVA_INT_UNALIGNED, 0L)
    fun readLong(addr: Long): Long? = snapshotInto(addr, 8L)?.get(JAVA_LONG_UNALIGNED, 0L)
    fun readFloat(addr: Long): Float? = snapshotInto(addr, 4L)?.get(JAVA_FLOAT_UNALIGNED, 0L)
    fun readPointer(addr: Long): Long? = snapshotInto(addr, 8L)?.get(ADDRESS_UNALIGNED, 0L)?.address()

    /** True iff `[addr, addr+size)` is fully mapped. Probes by snapshotting and discarding. */
    fun isReadable(addr: Long, size: Long): Boolean = snapshotInto(addr, size) != null
}
