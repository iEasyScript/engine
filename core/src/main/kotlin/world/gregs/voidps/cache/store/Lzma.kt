package world.gregs.voidps.cache.store

import lzma.sdk.lzma.Decoder
import lzma.sdk.lzma.Encoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Which LZMA encoder configuration reproduces a container's payload.
 *
 * Jagex's packer is the LZMA SDK in its fast (greedy) mode with 32 fast bytes, which the SDK's own
 * Java encoder reproduces byte for byte; the two match finders both occur in the model index, so
 * the variant is detected on unpack and recorded when it is not the default.
 */
enum class LzmaVariant(val id: String, val algorithm: Int, val matchFinder: Int, val fastBytes: Int) {
    FAST_BT4("fast-bt4", 0, 1, 32),
    FAST_BT2("fast-bt2", 0, 0, 32),
    NORMAL_BT4("normal-bt4", 1, 1, 32),
    NORMAL_BT4_64("normal-bt4-64", 1, 1, 64);

    companion object {
        fun of(id: String): LzmaVariant = entries.firstOrNull { it.id == id.lowercase() }
            ?: throw IllegalArgumentException("Unknown lzma variant '$id'.")
    }
}

/**
 * Raw LZMA streams as the cache stores them: the five property bytes the SDK writes (the packed
 * lc/lp/pb byte and the little endian dictionary size) followed by the stream, with no end marker
 * because the container header already says how long the output is.
 */
object Lzma {

    /** Bytes of the property header every LZMA container starts its payload with. */
    const val PROPERTIES_SIZE = 5

    /** `lc=3 lp=0 pb=2`, dictionary 4 MiB: what every Jagex LZMA container so far has carried. */
    val DEFAULT_PROPERTIES: ByteArray = byteArrayOf(0x5d, 0, 0, 0x40, 0)

    private val decoders = ThreadLocal.withInitial { Decoder() }

    /**
     * One encoder per thread and configuration: an encoder resets its state on every call and only
     * rebuilds its dictionary-sized match finder when the dictionary changes, so reusing it turns a
     * hundred megabytes of allocation per archive into none.
     */
    private val encoders = ThreadLocal.withInitial { HashMap<String, Encoder>() }

    fun decompress(payload: ByteArray, decompressedSize: Int): ByteArray {
        require(payload.size >= PROPERTIES_SIZE) { "LZMA payload of ${payload.size} bytes has no property header." }
        val decoder = decoders.get()
        val properties = payload.copyOf(PROPERTIES_SIZE)
        require(decoder.setDecoderProperties(properties)) { "LZMA property header ${hex(properties)} is invalid." }
        val output = ByteArray(decompressedSize)
        val input = ByteArrayInputStream(payload, PROPERTIES_SIZE, payload.size - PROPERTIES_SIZE)
        val sink = FixedOutputStream(output)
        check(decoder.code(input, sink, decompressedSize.toLong())) { "LZMA stream is corrupt." }
        check(sink.position == decompressedSize) { "LZMA stream produced ${sink.position} of $decompressedSize bytes." }
        return output
    }

    /** [data] compressed with [variant] under the [properties] header, header included. */
    fun compress(data: ByteArray, properties: ByteArray = DEFAULT_PROPERTIES, variant: LzmaVariant = LzmaVariant.FAST_BT4): ByteArray {
        require(properties.size == PROPERTIES_SIZE) { "LZMA properties are $PROPERTIES_SIZE bytes, not ${properties.size}." }
        val packed = properties[0].toInt() and 0xff
        val lc = packed % 9
        val lp = (packed / 9) % 5
        val pb = packed / 45
        val dictionary = (properties[1].toInt() and 0xff) or
            ((properties[2].toInt() and 0xff) shl 8) or
            ((properties[3].toInt() and 0xff) shl 16) or
            ((properties[4].toInt() and 0xff) shl 24)
        val encoder = encoders.get().getOrPut("${variant.id}:${hex(properties)}") {
            Encoder().also {
                it.setAlgorithm(variant.algorithm)
                it.setMatchFinder(variant.matchFinder)
                it.setNumFastBytes(variant.fastBytes)
                it.setDictionarySize(dictionary)
                it.setLcLpPb(lc, lp, pb)
                it.setEndMarkerMode(false)
            }
        }
        val output = ByteArrayOutputStream(data.size / 2 + PROPERTIES_SIZE + 16)
        output.write(properties)
        encoder.code(ByteArrayInputStream(data), output, -1, -1, null)
        return output.toByteArray()
    }

    /** The property header a stored LZMA [payload] carries. */
    fun properties(payload: ByteArray): ByteArray = payload.copyOf(PROPERTIES_SIZE)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0) { "'$text' is not a whole number of bytes." }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private class FixedOutputStream(private val target: ByteArray) : OutputStream() {
        var position = 0
            private set

        override fun write(b: Int) {
            check(position < target.size) { "LZMA stream is longer than its declared ${target.size} bytes." }
            target[position++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(position + len <= target.size) { "LZMA stream is longer than its declared ${target.size} bytes." }
            System.arraycopy(b, off, target, position, len)
            position += len
        }
    }
}
