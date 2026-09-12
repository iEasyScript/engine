package org.projectx.core

import lzma.sdk.lzma.Decoder
import lzma.sdk.lzma.Encoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The LZMA "alone" (.lzma) stream format Jagex serves the NXT client binary in: 5 bytes of coder
 * properties, an 8-byte little-endian uncompressed size, then the raw LZMA stream.
 *
 * Same `lzma.sdk` codec the cache library uses for container type 3; only the header placement
 * differs, living in the file rather than an RS container header.
 */
object LzmaAlone {
    private const val PROPS_LEN = 5
    private const val SIZE_LEN = 8
    private const val HEADER_LEN = PROPS_LEN + SIZE_LEN

    /** Matches the dictionary Jagex's own client streams advertise; the SDK would default to 4 MiB. */
    private const val DICTIONARY_SIZE = 1 shl 23

    /** Number of header bytes needed to read the embedded uncompressed size (without decompressing). */
    const val MIN_HEADER = HEADER_LEN

    /** Reads the embedded uncompressed size from an alone-format header (first 13 bytes suffice). */
    fun uncompressedSize(header: ByteArray): Long {
        require(header.size >= HEADER_LEN) { "LZMA header too short: ${header.size} < $HEADER_LEN" }
        return ByteBuffer.wrap(header, PROPS_LEN, SIZE_LEN).order(ByteOrder.LITTLE_ENDIAN).long
    }

    /** Decompresses a complete alone-format stream into the original binary. */
    fun decompress(data: ByteArray): ByteArray {
        require(data.size > HEADER_LEN) { "LZMA stream too short: ${data.size} bytes" }
        val size = uncompressedSize(data)
        require(size in 0..Int.MAX_VALUE.toLong()) { "Unreasonable LZMA uncompressed size: $size" }

        val props = data.copyOfRange(0, PROPS_LEN)
        val decoder = Decoder()
        require(decoder.setDecoderProperties(props)) { "LZMA: bad decoder properties" }

        val out = ByteArray(size.toInt())
        val input = ByteArrayInputStream(data, HEADER_LEN, data.size - HEADER_LEN)
        decoder.code(input, FixedArrayOutputStream(out), size)
        return out
    }

    /** Compresses [data] into a complete alone-format stream. */
    fun compress(data: ByteArray): ByteArray {
        val encoder = Encoder()
        encoder.setDictionarySize(DICTIONARY_SIZE)
        val out = ByteArrayOutputStream(data.size / 3)
        encoder.writeCoderProperties(out)
        val size = data.size.toLong()
        repeat(SIZE_LEN) { i -> out.write(((size ushr (8 * i)) and 0xFF).toInt()) }
        encoder.code(ByteArrayInputStream(data), out, size, -1L, null)
        return out.toByteArray()
    }

    /** Writes straight into a preallocated array — the decompressed length is known up front. */
    private class FixedArrayOutputStream(private val target: ByteArray) : OutputStream() {
        private var pos = 0
        override fun write(b: Int) {
            target[pos++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            System.arraycopy(b, off, target, pos, len)
            pos += len
        }
    }
}
