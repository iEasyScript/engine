package world.gregs.voidps.cache.compress

import lzma.sdk.lzma.Decoder
import org.projectx.core.Logger.logError
import org.projectx.core.Logger.logWarn
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.secure.Xtea
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater

/**
 * Context per thread for decompressing data in parallel
 */
class DecompressionContext : AutoCloseable {
    private val gzipInflater = Inflater(true)
    private val bzip2Compressor: BZIP2Compressor by lazy { BZIP2Compressor() }
    private val lzmaDecoder: Decoder by lazy { Decoder() }

    override fun close() {
        gzipInflater.end()
    }

    fun decompress(data: ByteArray, keys: IntArray? = null): ByteArray? {
        // Check for ZLIB magic (ZLB) before standard container format
        if (data.size >= 3 && data[0] == 0x5A.toByte() && data[1] == 0x4C.toByte() && data[2] == 0x42.toByte()) {
            return decompressZlib(data)
        }

        if (keys != null && (keys[0] != 0 || keys[1] != 0 || keys[2] != 0 || 0 != keys[3])) {
            Xtea.decipher(data, keys, 5)
        }
        val buffer = BufferReader(data)
        val type = buffer.readUnsignedByte()
        val compressedSize = buffer.readInt()
        var decompressedSize = 0
        if (type != 0) {
            decompressedSize = buffer.readInt()
        }
        when (type) {
            NONE -> {
                val decompressed = ByteArray(compressedSize)
                buffer.readBytes(decompressed, 0, compressedSize)
                return decompressed
            }
            BZIP2 -> {
                if (!warned.get()) {
                    logWarn("BZIP2 Compression found - replace to improve read performance.")
                    warned.set(true)
                }
                val decompressed = ByteArray(decompressedSize)
                bzip2Compressor.decompress(decompressed, decompressedSize, data, 9)
                return decompressed
            }
            GZIP -> {
                val offset = buffer.position()
                if (buffer.readByte() != 31 || buffer.readByte() != -117) {
                    return null
                }
                return try {
                    val decompressed = ByteArray(decompressedSize)
                    gzipInflater.setInput(data, offset + 10, data.size - (offset + 18))
                    var count = 0
                    while (count < decompressedSize && !gzipInflater.finished()) {
                        val inflated = gzipInflater.inflate(decompressed, count, decompressedSize - count)
                        if (inflated == 0) {
                            break
                        }
                        count += inflated
                    }
                    if (count != decompressedSize) {
                        logWarn("GZIP size mismatch: expected $decompressedSize bytes, inflated $count.")
                    }
                    decompressed
                } catch (exception: Exception) {
                    logWarn("Error decompressing gzip data.")
                    null
                } finally {
                    gzipInflater.reset()
                }
            }
            LZMA -> {
                val decompressed = ByteArray(decompressedSize)
                decompress(data, buffer.position(), decompressed, decompressedSize)
                return decompressed
            }
        }
        return null
    }

    private fun decompress(compressed: ByteArray, propsOffset: Int, decompressed: ByteArray, decompressedLength: Int) {
        // LZMA properties are 5 bytes at propsOffset (offset 9 in the container).
        // Extract them into a separate array for setDecoderProperties.
        val props = ByteArray(5)
        System.arraycopy(compressed, propsOffset, props, 0, 5)
        if (!lzmaDecoder.setDecoderProperties(props)) {
            logError("LZMA: Bad properties.")
            return
        }
        // Compressed stream starts after the 5-byte properties header
        val streamOffset = propsOffset + 5
        val input = ByteArrayInputStream(compressed, streamOffset, compressed.size - streamOffset)
        val output = ByteArrayWrapperOutputStream(decompressed)
        lzmaDecoder.code(input, output, decompressedLength.toLong())
    }

    private class ByteArrayWrapperOutputStream(private val byteArray: ByteArray) : OutputStream() {
        private var position = 0

        override fun write(b: Int) {
            byteArray[position++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            System.arraycopy(b, off, byteArray, position, len)
            position += len
        }

        override fun flush() {
        }

        override fun close() {
        }
    }

    private fun decompressZlib(data: ByteArray): ByteArray? {
        // ZLB container (NXT on-disk group): "ZLB" magic (3) + version (1) + uncompressed length (4)
        // + zlib stream. The length must be read AFTER the version byte; reading it one byte early
        // consumes the version + 3 length bytes, producing a bogus ~16 MB size and a huge zero-padded
        // array whose length no longer describes the group (the group-split detection relies on it).
        val buffer = BufferReader(data)
        buffer.skip(4)
        val decompressedSize = buffer.readInt()
        val offset = buffer.position()
        return try {
            val decompressed = ByteArray(decompressedSize)
            val inflater = Inflater()
            inflater.setInput(data, offset, data.size - offset)
            var count = 0
            while (count < decompressedSize && !inflater.finished()) {
                val inflated = inflater.inflate(decompressed, count, decompressedSize - count)
                if (inflated == 0) break
                count += inflated
            }
            inflater.end()
            if (count != decompressedSize) {
                logWarn("ZLIB size mismatch: expected $decompressedSize bytes, inflated $count.")
            }
            decompressed
        } catch (e: Exception) {
            logWarn("Error decompressing ZLIB data.")
            null
        }
    }

    companion object {
        private const val NONE = 0
        private const val BZIP2 = 1
        private const val GZIP = 2
        private const val LZMA = 3
        private val warned = AtomicBoolean()
    }
}