package org.projectx.packetlog.chunk

import java.io.ByteArrayOutputStream

/**
 * Unsigned LEB128. Every column in a chunk is non-negative by construction, so zigzag would spend a
 * bit per value on a sign that never occurs, and LEB128 is the one varint spelling every consumer's
 * toolchain already reads without a library.
 */
internal fun ByteArrayOutputStream.writeUVarInt(value: Long) {
    require(value >= 0) { "LEB128 here encodes non-negative values only, got $value" }
    var remaining = value
    while (true) {
        val part = (remaining and 0x7F).toInt()
        remaining = remaining ushr 7
        if (remaining == 0L) {
            write(part)
            return
        }
        write(part or 0x80)
    }
}

internal class VarIntReader(private val bytes: ByteArray, offset: Int, private val limit: Int) {

    var offset: Int = offset
        private set

    val exhausted: Boolean get() = offset >= limit

    fun readUVarInt(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (offset >= limit) throw ChunkFormatException("truncated varint at offset $offset")
            if (shift > 63) throw ChunkFormatException("varint wider than 64 bits at offset $offset")
            val byte = bytes[offset++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }
}

class ChunkFormatException(message: String) : RuntimeException(message)
