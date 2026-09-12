@file:Suppress("DEPRECATION")

package world.gregs.voidps.cache.store

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import world.gregs.voidps.cache.compress.BZIP2Compressor
import world.gregs.voidps.cache.compress.BZip2Encoder
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.secure.Whirlpool
import world.gregs.voidps.cache.secure.Xtea
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * One JS5 container - the unit an archive is stored and served as.
 *
 * ```
 * [u8 compression][i32 compressedSize]([i32 decompressedSize] if compression != 0)[payload]([u16 version])
 * ```
 *
 * A legacy sector store keeps a two byte version trailer on every archive container and none on
 * a reference table; the NXT SQLite store keeps the version in a column and no container carries
 * one. The trailer is the low 16 bits of the archive's version in its index's reference table and
 * is never sent over JS5, which is why the CRC and whirlpool the client validates cover the
 * container *without* it - see [crc] and [whirlpool].
 *
 * A [Container] holds the payload exactly as stored: for [Compression.GZIP] that includes the
 * gzip header and trailer, for [Compression.LZMA] the five property bytes, and for an encrypted
 * map archive it is the *deciphered* payload, so [encode] with the same keys reproduces the stored
 * bytes byte for byte.
 */
class Container(
    val compression: Compression,
    /** The compressed bytes as stored, deciphered. */
    val payload: ByteArray,
    /** Size of [data]; equal to `payload.size` when [compression] is [Compression.NONE]. */
    val decompressedSize: Int,
    /** The two byte trailer, or null when the container has none. */
    val version: Int?
) {

    /** Bytes before the payload: 5 for [Compression.NONE], 9 otherwise. */
    val headerSize: Int
        get() = if (compression == Compression.NONE) HEADER_SIZE_NONE else HEADER_SIZE_SIZED

    /** Length of the encoded container, trailer included. */
    val encodedSize: Int
        get() = headerSize + payload.size + if (version == null) 0 else TRAILER_SIZE

    /** The gzip header's OS byte, 0 for anything but a gzip container. */
    val gzipOs: Int
        get() = if (compression == Compression.GZIP && payload.size >= GZIP_HEADER_SIZE) payload[9].toInt() and 0xff else 0

    /** Decompress the payload. */
    fun data(): ByteArray = when (compression) {
        Compression.NONE -> payload.copyOf()
        Compression.GZIP -> inflate()
        Compression.BZIP2 -> bunzip()
        Compression.LZMA -> Lzma.decompress(payload, decompressedSize.also { requireSaneSize() })
    }

    private fun inflate(): ByteArray {
        requireSaneSize()
        require(payload.size >= GZIP_HEADER_SIZE + GZIP_TRAILER_SIZE) {
            "Gzip payload of ${payload.size} bytes is too short to hold a header and trailer."
        }
        require((payload[0].toInt() and 0xff) == 0x1f && (payload[1].toInt() and 0xff) == 0x8b) {
            "Container claims gzip but the payload has no gzip magic."
        }
        val inflater = inflaters.get()
        try {
            inflater.setInput(payload, GZIP_HEADER_SIZE, payload.size - GZIP_HEADER_SIZE - GZIP_TRAILER_SIZE)
            val output = ByteArray(decompressedSize)
            var written = 0
            while (written < decompressedSize && !inflater.finished()) {
                val read = inflater.inflate(output, written, decompressedSize - written)
                if (read == 0) break
                written += read
            }
            check(written == decompressedSize) { "Inflated $written of $decompressedSize bytes." }
            return output
        } finally {
            inflater.reset()
        }
    }

    private fun bunzip(): ByteArray {
        requireSaneSize()
        val output = ByteArray(decompressedSize)
        bzip2.get().decompress(output, decompressedSize, payload, 0)
        return output
    }

    /**
     * A decompressed size out of range means the header was never really decrypted - the field
     * sits inside the encrypted region, so a wrong or missing xtea key shows up here first.
     */
    private fun requireSaneSize() {
        require(decompressedSize in 0..MAX_DECOMPRESSED_SIZE) {
            "Container claims $decompressedSize decompressed bytes; the xtea key is wrong or missing."
        }
    }

    /**
     * The container as stored: header, payload and trailer.
     *
     * When [xtea] is given and not all zero the region from offset 5 to the end of the payload
     * is enciphered - the decompressed size int is inside the encrypted region, which is what
     * the client's own `decryptXtea(keys, containerLength)` assumes.
     */
    fun encode(xtea: IntArray? = null): ByteArray {
        val header = headerSize
        val output = ByteArray(encodedSize)
        output[0] = compression.id.toByte()
        putInt(output, 1, payload.size)
        if (compression != Compression.NONE) {
            putInt(output, 5, decompressedSize)
        }
        System.arraycopy(payload, 0, output, header, payload.size)
        if (version != null) {
            val trailer = header + payload.size
            output[trailer] = (version shr 8).toByte()
            output[trailer + 1] = version.toByte()
        }
        if (encrypted(xtea)) {
            Xtea.encipher(output, XTEA_OFFSET, header + payload.size, xtea!!)
        }
        return output
    }

    /**
     * CRC32 of the encoded container without the version trailer, which is the value the
     * reference table stores and the client validates a downloaded archive against.
     */
    fun crc(xtea: IntArray? = null): Int {
        val encoded = encode(xtea)
        return CRC.calculate(encoded, 0, encoded.size - if (version == null) 0 else TRAILER_SIZE)
    }

    /** Whirlpool of the same range [crc] covers. */
    fun whirlpool(xtea: IntArray? = null): ByteArray {
        val encoded = encode(xtea)
        return whirlpool(encoded, encoded.size - if (version == null) 0 else TRAILER_SIZE)
    }

    /**
     * The settings that reproduce this container's payload from [data], or null when no encoder
     * here does - in which case the tree keeps the payload itself.
     */
    fun settings(data: ByteArray): CompressionSettings? = when (compression) {
        Compression.NONE -> CompressionSettings.DEFAULT
        Compression.GZIP -> gzipSettings(data)
        Compression.BZIP2 -> bzip2Settings(data)
        Compression.LZMA -> lzmaSettings(data)
    }

    private fun gzipSettings(data: ByteArray): CompressionSettings? {
        val os = gzipOs
        if (payload.size < GZIP_HEADER_SIZE + GZIP_TRAILER_SIZE) {
            return null
        }
        for (level in CompressionSettings.GZIP_LEVELS) {
            if (gzip(data, os, level).contentEquals(payload)) {
                return CompressionSettings(gzipLevel = level, gzipOs = os)
            }
        }
        return null
    }

    private fun bzip2Settings(data: ByteArray): CompressionSettings? {
        for (variant in BZip2Variant.entries) {
            if (bzip2(data, variant).contentEquals(payload)) {
                return CompressionSettings(bzip2 = variant)
            }
        }
        return null
    }

    private fun lzmaSettings(data: ByteArray): CompressionSettings? {
        if (payload.size < Lzma.PROPERTIES_SIZE) {
            return null
        }
        val properties = Lzma.properties(payload)
        for (variant in LzmaVariant.entries) {
            if (Lzma.compress(data, properties, variant).contentEquals(payload)) {
                return CompressionSettings(lzma = variant, lzmaProperties = properties)
            }
        }
        return null
    }

    companion object {
        const val HEADER_SIZE_NONE = 5
        const val HEADER_SIZE_SIZED = 9
        const val TRAILER_SIZE = 2

        /** XTEA over a container starts after the type byte and the compressed size. */
        const val XTEA_OFFSET = 5

        /** Nothing in a cache decompresses to more than this; a sector index entry's size is 24 bits. */
        const val MAX_DECOMPRESSED_SIZE = 0x7fffffff

        private const val GZIP_HEADER_SIZE = 10
        private const val GZIP_TRAILER_SIZE = 8

        /** libbzip2 block size the caches are built with, in hundreds of kilobytes. */
        private const val BZIP2_BLOCK_SIZE = 1

        /** `BZh<n>`, which a cache container omits. */
        private const val BZIP2_MAGIC_SIZE = 4

        private val inflaters = ThreadLocal.withInitial { Inflater(true) }

        private val bzip2 = ThreadLocal.withInitial { BZIP2Compressor() }

        private val deflaters = ThreadLocal.withInitial { HashMap<Int, Deflater>() }

        /**
         * Decode a stored container. [bytes] is never modified: an encrypted container is
         * deciphered on a copy.
         *
         * @param trailer whether the two bytes past the payload, when present, are a version
         *   trailer. A sector store's archive containers carry one; nothing else does, and a
         *   container that is followed by anything else is malformed.
         */
        fun decode(bytes: ByteArray, xtea: IntArray? = null, trailer: Boolean = true): Container {
            require(bytes.size >= HEADER_SIZE_NONE) { "Container of ${bytes.size} bytes is too short." }
            val compression = Compression.of(bytes[0].toInt() and 0xff)
            // Neither size is masked: the container has to re-encode byte for byte, and the
            // decompressed size lives inside the encrypted region, so a wrong xtea key leaves
            // garbage there that must still survive a decode/encode round trip untouched.
            val compressedSize = getInt(bytes, 1)
            val header = if (compression == Compression.NONE) HEADER_SIZE_NONE else HEADER_SIZE_SIZED
            val payloadEnd = header + compressedSize
            require(compressedSize >= 0 && payloadEnd <= bytes.size) {
                "Container claims $compressedSize payload bytes but only has ${bytes.size - header}."
            }
            val data = if (encrypted(xtea)) {
                bytes.copyOf().also { Xtea.decipher(it, xtea!!, XTEA_OFFSET, payloadEnd) }
            } else {
                bytes
            }
            val decompressedSize = if (compression == Compression.NONE) compressedSize else getInt(data, 5)
            val version = when (bytes.size - payloadEnd) {
                0 -> null
                TRAILER_SIZE -> if (trailer) {
                    ((data[payloadEnd].toInt() and 0xff) shl 8) or (data[payloadEnd + 1].toInt() and 0xff)
                } else {
                    throw IllegalArgumentException("Container has $TRAILER_SIZE trailing bytes but this store carries no trailer.")
                }
                else -> throw IllegalArgumentException(
                    "Container has ${bytes.size - payloadEnd} trailing bytes; expected 0 or $TRAILER_SIZE."
                )
            }
            return Container(compression, data.copyOfRange(header, payloadEnd), decompressedSize, version)
        }

        /**
         * Compress [data] into a container.
         *
         * gzip is written exactly the way the cache stores it: a standard ten byte header with a
         * zero MTIME and XFL, the OS byte, a raw deflate stream at the recorded level with the
         * default strategy, and the eight byte little endian CRC32 and size trailer. bzip2 is written
         * at block size 1 with the four byte magic stripped, and LZMA as the SDK's property header
         * followed by a stream with no end marker.
         */
        fun compress(
            data: ByteArray,
            compression: Compression,
            version: Int?,
            settings: CompressionSettings = CompressionSettings.DEFAULT
        ): Container {
            val payload = when (compression) {
                Compression.NONE -> data.copyOf()
                Compression.GZIP -> gzip(data, settings.gzipOs, settings.gzipLevel)
                Compression.BZIP2 -> bzip2(data, settings.bzip2)
                Compression.LZMA -> Lzma.compress(data, settings.lzmaProperties, settings.lzma)
            }
            return Container(compression, payload, data.size, version)
        }

        /** A container built from an already compressed [payload], for a payload the tree kept verbatim. */
        fun stored(compression: Compression, payload: ByteArray, decompressedSize: Int, version: Int?): Container =
            Container(compression, payload, decompressedSize, version)

        fun whirlpool(bytes: ByteArray, length: Int): ByteArray {
            val output = ByteArray(WHIRLPOOL_SIZE)
            val hash = Whirlpool()
            hash.reset()
            hash.add(bytes, 0, length)
            hash.finalize(output)
            return output
        }

        const val WHIRLPOOL_SIZE = 64

        private fun bzip2(data: ByteArray, variant: BZip2Variant): ByteArray = when (variant) {
            BZip2Variant.LIBBZIP2 -> BZip2Encoder.compress(data, BZIP2_BLOCK_SIZE)
            BZip2Variant.COMMONS -> commonsBzip2(data)
        }

        /** commons-compress writes the `BZh<n>` magic the cache strips; everything after it is the payload. */
        private fun commonsBzip2(data: ByteArray): ByteArray {
            val output = ByteArrayOutputStream(maxOf(64, data.size / 2))
            BZip2CompressorOutputStream(output, BZIP2_BLOCK_SIZE).use { stream ->
                stream.write(data)
            }
            val stream = output.toByteArray()
            return stream.copyOfRange(BZIP2_MAGIC_SIZE, stream.size)
        }

        private fun gzip(data: ByteArray, os: Int, level: Int): ByteArray {
            val deflater = deflaters.get().getOrPut(level) { Deflater(level, true) }
            val deflated: ByteArray
            try {
                deflater.setInput(data)
                deflater.finish()
                var output = ByteArray(data.size + (data.size / 8) + 64)
                var written = 0
                while (!deflater.finished()) {
                    if (written == output.size) {
                        output = output.copyOf(output.size * 2)
                    }
                    written += deflater.deflate(output, written, output.size - written)
                }
                deflated = output.copyOf(written)
            } finally {
                deflater.reset()
            }
            val payload = ByteArray(GZIP_HEADER_SIZE + deflated.size + GZIP_TRAILER_SIZE)
            payload[0] = 0x1f
            payload[1] = 0x8b.toByte()
            payload[2] = 8
            payload[9] = os.toByte()
            System.arraycopy(deflated, 0, payload, GZIP_HEADER_SIZE, deflated.size)
            val crc = CRC32()
            crc.update(data)
            putIntLittle(payload, GZIP_HEADER_SIZE + deflated.size, crc.value.toInt())
            putIntLittle(payload, GZIP_HEADER_SIZE + deflated.size + 4, data.size)
            return payload
        }

        private fun encrypted(xtea: IntArray?): Boolean =
            xtea != null && (xtea[0] != 0 || xtea[1] != 0 || xtea[2] != 0 || xtea[3] != 0)

        private fun getInt(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)

        private fun putInt(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value shr 24).toByte()
            data[offset + 1] = (value shr 16).toByte()
            data[offset + 2] = (value shr 8).toByte()
            data[offset + 3] = value.toByte()
        }

        private fun putIntLittle(data: ByteArray, offset: Int, value: Int) {
            data[offset] = value.toByte()
            data[offset + 1] = (value shr 8).toByte()
            data[offset + 2] = (value shr 16).toByte()
            data[offset + 3] = (value shr 24).toByte()
        }
    }
}
