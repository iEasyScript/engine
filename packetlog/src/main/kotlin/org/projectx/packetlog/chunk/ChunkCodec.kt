package org.projectx.packetlog.chunk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import lzma.sdk.lzma.Decoder
import lzma.sdk.lzma.Encoder
import org.projectx.packetlog.store.PacketSchema.Codec

/**
 * Compresses a whole chunk payload as one stream. Per-section or per-body framing costs far more in
 * overhead than it saves, so the only unit here is the entire payload.
 *
 * LZMA is the default because it measured ~27% smaller than both Deflate and zstd on real packet
 * bodies, and `lzma-java` already ships inside the engine jar - so it costs no dependency and no
 * native library in the game process. Its slow compression is irrelevant: sealing runs on its own
 * thread roughly once every three minutes.
 */
object ChunkCodec {

    /**
     * Large enough to cover a whole chunk's worth of bodies, so matches reach back across the entire
     * prot group rather than only the last few packets.
     */
    private const val DICTIONARY_SIZE = 1 shl 20

    private const val LZMA_PROPS_SIZE = 5

    /** LZMA's encoder holds several MB of match-finder state; one per thread, reused across chunks. */
    private val encoders = ThreadLocal.withInitial {
        Encoder().apply {
            setDictionarySize(DICTIONARY_SIZE)
            setNumFastBytes(64)
            setMatchFinder(Encoder.EMatchFinderTypeBT4)
            setLcLpPb(3, 0, 2)
            setEndMarkerMode(false)
        }
    }

    class Compressed(val codec: Codec, val bytes: ByteArray, val props: ByteArray)

    fun compress(codec: Codec, plain: ByteArray): Compressed = when (codec) {
        Codec.STORE -> Compressed(codec, plain, ByteArray(LZMA_PROPS_SIZE))
        Codec.DEFLATE_RAW -> Compressed(codec, deflate(plain), ByteArray(LZMA_PROPS_SIZE))
        Codec.LZMA1_RAW -> lzma(plain)
    }

    fun decompress(codec: Codec, stored: ByteArray, plainLength: Int, props: ByteArray): ByteArray =
        when (codec) {
            Codec.STORE -> stored.copyOf(plainLength)
            Codec.DEFLATE_RAW -> inflate(stored, plainLength)
            Codec.LZMA1_RAW -> unlzma(stored, plainLength, props)
        }

    private fun lzma(plain: ByteArray): Compressed {
        val encoder = encoders.get()
        val props = ByteArrayOutputStream(LZMA_PROPS_SIZE)
        encoder.writeCoderProperties(props)
        val out = ByteArrayOutputStream(plain.size / 2 + 64)
        encoder.code(ByteArrayInputStream(plain), out, plain.size.toLong(), -1L, null)
        return Compressed(Codec.LZMA1_RAW, out.toByteArray(), props.toByteArray())
    }

    private fun unlzma(stored: ByteArray, plainLength: Int, props: ByteArray): ByteArray {
        val decoder = Decoder()
        if (!decoder.setDecoderProperties(props)) throw ChunkFormatException("bad LZMA properties")
        val out = ByteArrayOutputStream(plainLength)
        if (!decoder.code(ByteArrayInputStream(stored), out, plainLength.toLong())) {
            throw ChunkFormatException("LZMA stream ended early")
        }
        return out.toByteArray().also {
            if (it.size != plainLength) throw ChunkFormatException("LZMA produced ${it.size} of $plainLength bytes")
        }
    }

    private fun deflate(plain: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(plain)
            deflater.finish()
            val out = ByteArrayOutputStream(plain.size / 2 + 64)
            val buffer = ByteArray(1 shl 16)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(stored: ByteArray, plainLength: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(stored)
            val plain = ByteArray(plainLength)
            var written = 0
            while (written < plainLength) {
                val produced = inflater.inflate(plain, written, plainLength - written)
                if (produced == 0) throw ChunkFormatException("deflate stream ended after $written of $plainLength bytes")
                written += produced
            }
            return plain
        } finally {
            inflater.end()
        }
    }
}
