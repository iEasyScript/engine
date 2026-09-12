package world.gregs.voidps.cache.file

import io.ktor.utils.io.*
import org.projectx.core.Logger.logInfo
import org.projectx.core.Logger.logTrace
import org.projectx.core.Logger.logWarn
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.file.type.CacheFileProvider
import world.gregs.voidps.cache.file.type.MemoryFileProvider

/**
 * Provides raw cache container data and serves it over the NXT JS5 protocol.
 *
 * Block tracking is PER-RESPONSE: each response starts its own block counter, matching the
 * NXT client's wire reader which tracks block position independently for each pending response.
 * Group data carries no version suffix; the client verifies the archive-index CRC over the
 * full container minus its trailing 2 bytes.
 */
interface FileProvider {
    fun data(index: Int, archive: Int): ByteArray?

    /**
     * Serves a single JS5 response with correct per-response block framing.
     *
     * @param write The output channel to write the response to.
     * @param ref Packed reference: (index << 32) | archive.
     * @param prefetch Whether this is a prefetch (non-urgent) response.
     * @param xorKey XOR encryption key (0 = no encryption).
     * @return true on success, false on failure.
     */
    suspend fun serve(write: ByteWriteChannel, ref: Long, prefetch: Boolean, xorKey: Int = 0): Boolean {
        val index = (ref ushr 32).toInt()
        val archive = (ref and 0xFFFFFFFFL).toInt()
        val data = data(index, archive)
        if (data == null || data.size < CONTAINER_HEADER_LEN) {
            logWarn("Unable to fulfill request $index $archive $prefetch.")
            return false
        }

        val hash = if (prefetch) archive or (1 shl 31) else archive
        val compression = data[0].toInt()
        val compressedSize = getInt(data[1], data[2], data[3], data[4])

        val payloadSize = compressedSize + if (compression != 0) 4 else 0
        val available = data.size - CONTAINER_HEADER_LEN
        if (payloadSize > available) {
            logWarn("Truncated container: index=$index archive=$archive header says $payloadSize bytes but only $available available — client CRC will fail.")
        }
        val actualPayloadSize = minOf(payloadSize, available)

        val headerBytes = ByteArray(RESPONSE_HEADER_LEN)
        headerBytes[0] = index.toByte()
        headerBytes[1] = (hash shr 24).toByte()
        headerBytes[2] = (hash shr 16).toByte()
        headerBytes[3] = (hash shr 8).toByte()
        headerBytes[4] = hash.toByte()
        headerBytes[5] = compression.toByte()
        headerBytes[6] = (compressedSize shr 24).toByte()
        headerBytes[7] = (compressedSize shr 16).toByte()
        headerBytes[8] = (compressedSize shr 8).toByte()
        headerBytes[9] = compressedSize.toByte()

        val continuationHeader = ByteArray(CONTINUATION_HEADER_LEN)
        continuationHeader[0] = index.toByte()
        continuationHeader[1] = (hash shr 24).toByte()
        continuationHeader[2] = (hash shr 16).toByte()
        continuationHeader[3] = (hash shr 8).toByte()
        continuationHeader[4] = hash.toByte()

        // Exact output size: the first block holds BLOCK_SIZE content bytes, every
        // subsequent block holds BLOCK_SIZE - CONTINUATION_HEADER_LEN content bytes.
        val total = RESPONSE_HEADER_LEN + actualPayloadSize
        val continuations = if (total > BLOCK_SIZE) {
            (total - BLOCK_SIZE + (BLOCK_SIZE - CONTINUATION_HEADER_LEN) - 1) / (BLOCK_SIZE - CONTINUATION_HEADER_LEN)
        } else {
            0
        }
        val buf = ByteArray(total + continuations * CONTINUATION_HEADER_LEN)
        var pos = 0
        var blockOffset = 0

        fun writeWithFraming(src: ByteArray, srcOffset: Int, length: Int) {
            var srcPos = srcOffset
            var rem = length
            while (rem > 0) {
                val blockRemaining = BLOCK_SIZE - blockOffset
                if (blockRemaining == 0) {
                    System.arraycopy(continuationHeader, 0, buf, pos, CONTINUATION_HEADER_LEN)
                    pos += CONTINUATION_HEADER_LEN
                    blockOffset = CONTINUATION_HEADER_LEN
                    continue
                }
                val toWrite = minOf(blockRemaining, rem)
                System.arraycopy(src, srcPos, buf, pos, toWrite)
                pos += toWrite
                srcPos += toWrite
                rem -= toWrite
                blockOffset += toWrite
            }
        }

        writeWithFraming(headerBytes, 0, RESPONSE_HEADER_LEN)
        writeWithFraming(data, CONTAINER_HEADER_LEN, actualPayloadSize)

        if (pos != buf.size) {
            logWarn("JS5 framing size mismatch: index=$index archive=$archive expected ${buf.size} bytes, wrote $pos.")
        }
        val response = if (pos == buf.size) buf else buf.copyOfRange(0, pos)
        if (xorKey != 0) {
            for (i in response.indices) {
                response[i] = (response[i].toInt() xor xorKey).toByte()
            }
        }
        write.writeFully(response)
        write.flush()
        logTrace("JS5 served: index=$index archive=$archive prefetch=$prefetch — $pos bytes")
        return true
    }

    companion object {
        fun load(cache: Cache, inMemory: Boolean = true): FileProvider {
            val start = System.currentTimeMillis()
            val provider = if (inMemory) MemoryFileProvider(cache) else CacheFileProvider(cache)
            logInfo("Loaded file provider in ${System.currentTimeMillis() - start}ms")
            return provider
        }

        internal fun getInt(b1: Byte, b2: Byte, b3: Byte, b4: Byte) =
            b1.toInt() shl 24 or (b2.toInt() and 0xff shl 16) or (b3.toInt() and 0xff shl 8) or (b4.toInt() and 0xff)

        internal const val BLOCK_SIZE = 102400
        internal const val RESPONSE_HEADER_LEN = 10
        internal const val CONTINUATION_HEADER_LEN = 5
        internal const val CONTAINER_HEADER_LEN = 5
    }
}
