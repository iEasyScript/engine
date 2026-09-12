package com.projectx.game.platform

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Reads process memory without trusting the pointer. Both implementations let the kernel do the
 * page-validity check and report failure, so a torn or stale pointer returns null instead of
 * faulting the game.
 */
interface MemoryReader {
    /** Fills `dst[0, len)` from `addr`. Returns false if any byte of the range is unreadable. */
    fun read(addr: Long, dst: ByteArray, len: Int): Boolean
}

private object ProcSelfMemReader : MemoryReader {
    // The kernel validates the mapping on the pread, so an unmapped range surfaces as IOException
    // rather than SIGSEGV.
    private val channel: FileChannel = FileChannel.open(Path.of("/proc/self/mem"), StandardOpenOption.READ)

    override fun read(addr: Long, dst: ByteArray, len: Int): Boolean {
        val buf = ByteBuffer.wrap(dst, 0, len)
        var pos = 0
        while (pos < len) {
            val n = try {
                channel.read(buf, addr + pos)
            } catch (_: IOException) {
                return false
            }
            if (n <= 0) return false
            pos += n
        }
        return true
    }
}

private object ReadProcessMemoryReader : MemoryReader {
    private val arena = Arena.ofShared()
    private val kernel32 = SymbolLookup.libraryLookup("kernel32.dll", arena)

    private val getCurrentProcess = Linker.nativeLinker().downcallHandle(
        kernel32.find("GetCurrentProcess").orElseThrow(),
        FunctionDescriptor.of(ADDRESS),
    )

    // BOOL ReadProcessMemory(HANDLE, LPCVOID, LPVOID, SIZE_T, SIZE_T*) — returns 0 and sets
    // ERROR_PARTIAL_COPY when the range crosses into unmapped memory, which is the check we want.
    private val readProcessMemory = Linker.nativeLinker().downcallHandle(
        kernel32.find("ReadProcessMemory").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS),
    )

    private val selfHandle: MemorySegment by lazy { getCurrentProcess.invokeExact() as MemorySegment }

    override fun read(addr: Long, dst: ByteArray, len: Int): Boolean {
        Arena.ofConfined().use { scratch ->
            val buffer = scratch.allocate(len.toLong())
            val written = scratch.allocate(JAVA_LONG)
            val ok = readProcessMemory.invokeExact(
                selfHandle,
                MemorySegment.ofAddress(addr),
                buffer,
                len.toLong(),
                written,
            ) as Int
            if (ok == 0 || written.get(JAVA_LONG, 0L) != len.toLong()) return false
            MemorySegment.ofArray(dst).copyFrom(buffer.asSlice(0L, len.toLong()))
            return true
        }
    }
}

object PlatformMemory : MemoryReader by resolveReader()

private fun resolveReader(): MemoryReader = when (Platform.current) {
    Platform.WINDOWS -> ReadProcessMemoryReader
    else -> ProcSelfMemReader
}
