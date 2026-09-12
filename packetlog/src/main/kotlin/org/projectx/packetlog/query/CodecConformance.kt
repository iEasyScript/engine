package org.projectx.packetlog.query

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write
import io.ktor.utils.io.asByteWriteChannel
import org.projectx.core.net.prot.Codec
import org.projectx.core.net.prot.ClientProt
import org.projectx.core.net.prot.ServerProt
import org.projectx.core.net.prot.decode.DecodeStatus
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.upload.ArchiveReader

/**
 * Checks our reading of the protocol against packets the real game actually sent.
 *
 * Three questions, in increasing strength:
 *
 *  - **Does it decode at all?** A decoder that throws on real bytes is wrong about something.
 *  - **Does it consume the whole packet?** Bytes left over prove the decoder does not describe all
 *    of it, even when what it did read looks sensible.
 *  - **Does it round-trip?** Decoding and then re-encoding with our own encoder and getting the
 *    original bytes back is the strongest check available: it exercises both directions at once,
 *    and it is the same encoder the server uses to talk to a real client. A mismatch means one of
 *    the two is wrong, and the diff says where.
 *
 * The last one is what makes a capture worth keeping. Anyone can write a decoder that produces
 * plausible numbers; only real bytes can say whether it produced the right ones.
 */
class CodecConformance(private val codec: Codec) {

    class ProtReport(
        val name: String,
        val direction: Int,
        val opcode: Int,
        val seen: Int,
        val decoded: Int,
        val threw: Int,
        val trailing: Int,
        val roundTripped: Int,
        val roundTripFailed: Int,
        val hasDecoder: Boolean,
        val hasEncoder: Boolean,
        val sampleMismatch: String?,
        val lengths: Set<Int>,
    ) {
        val undecoded: Int get() = seen - decoded

        /** A packet type nothing can read, ranked by how much of the capture it accounts for. */
        val opaque: Boolean get() = !hasDecoder
    }

    fun check(archive: File, limitPerProt: Int = 400): List<ProtReport> {
        val seen = HashMap<Pair<Int, Int>, Counters>()

        ArchiveReader(archive).use { reader ->
            for (session in reader.sessions()) {
                for (chunk in reader.chunks(session.id)) {
                    for (event in ChunkFrame.open(chunk.frame, chunk.firstSeq)) {
                        if (event.body.isEmpty()) continue
                        val counters = seen.getOrPut(event.dir to event.opcode) { Counters() }
                        counters.seen++
                        counters.lengths.add(event.body.size)
                        if (counters.examined >= limitPerProt) continue
                        counters.examined++
                        examine(event.dir, event.opcode, event.body, counters)
                    }
                }
            }
        }

        return seen.map { (key, counters) ->
            val (dir, opcode) = key
            val name = if (dir == 0) codec.serverProtName(opcode) else codec.clientProtName(opcode)
            ProtReport(
                name = name,
                direction = dir,
                opcode = opcode,
                seen = counters.seen,
                decoded = counters.decoded,
                threw = counters.threw,
                trailing = counters.trailing,
                roundTripped = counters.roundTripped,
                roundTripFailed = counters.roundTripFailed,
                hasDecoder = codec.structuredDecoder(dir, opcode) != null ||
                    (dir == 1 && codec.clientProtsByOpcode[opcode]?.decoder != null),
                hasEncoder = counters.roundTripped + counters.roundTripFailed > 0,
                sampleMismatch = counters.sampleMismatch,
                lengths = counters.lengths,
            )
        }.sortedByDescending { it.seen }
    }

    private class Counters {
        var seen = 0
        var examined = 0
        var decoded = 0
        var threw = 0
        var trailing = 0
        var roundTripped = 0
        var roundTripFailed = 0
        var sampleMismatch: String? = null
        val lengths = HashSet<Int>()
    }

    private fun examine(dir: Int, opcode: Int, body: ByteArray, counters: Counters) {
        val structured = codec.structuredDecoder(dir, opcode)
        if (structured != null) {
            val decoded = runCatching {
                runBlocking { structured.decode.invoke(Buffer().apply { write(body) }, body.size) }
            }.getOrElse {
                counters.threw++
                return
            }
            when (decoded.status) {
                DecodeStatus.FAILED -> counters.threw++
                DecodeStatus.PARTIAL -> counters.trailing++
                else -> counters.decoded++
            }
            // Where the decoded fields can be rebuilt into the typed prot, put our own encoder
            // against what the game sent. This is the check that proves both directions at once.
            structured.reconstruct?.invoke(decoded)?.let { roundTrip(it, body, counters) }
            return
        }

        // Client prots have a real typed decoder; round-tripping them is not possible without an
        // encoder for that direction, so consuming the whole payload is the check available.
        if (dir == 1) {
            val clientDecoder = codec.clientProtsByOpcode[opcode]?.decoder ?: return
            val source = Buffer().apply { write(body) }
            val prot = runCatching { runBlocking { clientDecoder.invoke(source, body.size) } }
                .getOrElse {
                    counters.threw++
                    return
                }
            counters.decoded++
            if (!source.exhausted()) counters.trailing++
            roundTrip(prot, body, counters)
        }
    }

    /**
     * Re-encodes a decoded packet and compares against what the game sent. Only possible where an
     * encoder exists for the same type, which is why this is reported separately rather than
     * folded into the decode count.
     */
    private fun roundTrip(prot: Any, original: ByteArray, counters: Counters) {
        val encoder = when (prot) {
            is ServerProt -> codec.serverProts[prot::class]?.encoder
            is ClientProt -> null
            else -> null
        } ?: return

        val reencoded = runCatching {
            val buffer = Buffer()
            val channel = buffer.asByteWriteChannel()
            runBlocking {
                encoder.invoke(prot as ServerProt, channel)
                channel.flush()
            }
            buffer.readByteArray()
        }.getOrElse {
            counters.roundTripFailed++
            return
        }

        if (reencoded.contentEquals(original)) {
            counters.roundTripped++
        } else {
            counters.roundTripFailed++
            if (counters.sampleMismatch == null) {
                counters.sampleMismatch = "sent=${original.toHex()} ours=${reencoded.toHex()}"
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
}
