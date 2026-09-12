package org.projectx.packetlog.chunk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32C
import org.projectx.packetlog.store.PacketSchema.Codec
import org.projectx.packetlog.store.PacketSchema.Quality

/** One captured packet, as it enters and leaves a chunk. */
class ChunkEvent(
    val seq: Long,
    val epochMs: Long,
    val monoNs: Long,
    val gameTick: Int,
    val dir: Int,
    val opcode: Int,
    val quality: Quality,
    val body: ByteArray,
)

/** Per-prot totals for one chunk, mirrored into `chunk_prot` so SQL can prune without decompressing. */
class ChunkProtStat(
    val dir: Int,
    val opcode: Int,
    val localIndex: Int,
    val packetCount: Int,
    val bodyBytes: Long,
    val firstTick: Int,
    val lastTick: Int,
)

class SealedChunk(
    val frame: ByteArray,
    val codec: Codec,
    val count: Int,
    val firstSeq: Long,
    val firstMs: Long,
    val lastMs: Long,
    val firstMonoNs: Long,
    val firstTick: Int,
    val lastTick: Int,
    val bodyBytes: Long,
    val plainBytes: Int,
    val plainSha256: ByteArray,
    val protStats: List<ChunkProtStat>,
)

/**
 * The `UPK1` chunk format.
 *
 * Bodies compress far better grouped by prot, but a reader wants arrival order. Storing a
 * permutation to get both would cost roughly a quarter of the entire storage budget, so instead the
 * index columns stay in arrival order and only [SID_BODY] is grouped: the prot column in arrival
 * order already *is* the interleaving, and replaying it against a per-prot cursor rebuilds the
 * original sequence exactly. That also keeps the timestamp deltas monotonic, and resolves ties
 * within a millisecond by position - which is how byte-identical repeats of a packet keep their
 * true relative order.
 *
 * The payload carries a section directory so a reader can skip a section written by a newer writer
 * instead of rejecting the whole frame.
 */
object ChunkFrame {

    const val MAGIC = 0x314B5055
    const val FORMAT_VERSION = 1
    const val HEADER_SIZE = 40

    private const val SID_DT = 0x01
    private const val SID_PROT = 0x02
    private const val SID_PROTDICT = 0x03
    private const val SID_SIZE = 0x04
    private const val SID_TICK = 0x05
    private const val SID_BODY = 0x06
    private const val SID_QFLAG = 0x07

    private const val FLAG_BODIES_PROT_GROUPED = 1 shl 0
    private const val FLAG_HAS_TICK = 1 shl 1
    private const val FLAG_HAS_QFLAG = 1 shl 2

    fun seal(events: List<ChunkEvent>, codec: Codec = Codec.LZMA1_RAW): SealedChunk {
        require(events.isNotEmpty()) { "a chunk needs at least one event" }

        val protKeys = LinkedHashMap<Long, Int>()
        val protIndex = IntArray(events.size)
        for ((i, event) in events.withIndex()) {
            val key = (event.dir.toLong() shl 32) or (event.opcode.toLong() and 0xFFFFFFFFL)
            protIndex[i] = protKeys.getOrPut(key) { protKeys.size }
        }

        val firstMs = events.first().epochMs
        val firstTick = events.first().gameTick
        val payload = encodePayload(events, protIndex, protKeys, firstMs, firstTick)

        val plainSha = MessageDigest.getInstance("SHA-256").digest(payload)
        val compressed = ChunkCodec.compress(codec, payload)
        val frame = buildFrame(compressed, payload, events, protKeys.size)

        return SealedChunk(
            frame = frame,
            codec = compressed.codec,
            count = events.size,
            firstSeq = events.first().seq,
            firstMs = firstMs,
            lastMs = events.maxOf { it.epochMs },
            firstMonoNs = events.first().monoNs,
            firstTick = firstTick,
            lastTick = events.maxOf { it.gameTick },
            bodyBytes = events.sumOf { it.body.size.toLong() },
            plainBytes = payload.size,
            plainSha256 = plainSha,
            protStats = protStats(events, protIndex, protKeys),
        )
    }

    /**
     * [firstSeq] rebases the session-scoped sequence, which the frame does not store because element
     * position already carries it. `monoNs` is likewise not per-event: the chunk row anchors the
     * whole frame to the monotonic clock and `epochMs` carries timing within it, so events read back
     * here report zero. Everything else decodes from the frame alone, with no database context.
     */
    fun open(frame: ByteArray, firstSeq: Long = 0L): List<ChunkEvent> {
        if (frame.size < HEADER_SIZE) throw ChunkFormatException("frame shorter than its header")
        val header = ByteBuffer.wrap(frame, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        if (header.getInt(0) != MAGIC) throw ChunkFormatException("bad magic")
        val format = frame[4].toInt() and 0xFF
        if (format != FORMAT_VERSION) throw ChunkFormatException("unsupported chunk format v$format")

        val headerCrc = CRC32C().apply { update(frame, 0, HEADER_SIZE - 4) }.value.toInt()
        if (headerCrc != header.getInt(36)) throw ChunkFormatException("header checksum mismatch")

        val codec = Codec.entries.firstOrNull { it.code == (frame[5].toInt() and 0xFF) }
            ?: throw ChunkFormatException("unknown codec ${frame[5].toInt() and 0xFF}")
        val count = header.getInt(12)
        val plainLength = header.getInt(16)
        val compLength = header.getInt(20)
        val expectedCrc = header.getInt(24)
        val props = frame.copyOfRange(28, 33)

        if (HEADER_SIZE + compLength > frame.size) throw ChunkFormatException("frame truncated")
        val stored = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + compLength)
        val payload = ChunkCodec.decompress(codec, stored, plainLength, props)

        val actualCrc = CRC32C().apply { update(payload) }.value.toInt()
        if (actualCrc != expectedCrc) throw ChunkFormatException("payload checksum mismatch")

        return decodePayload(payload, count, firstSeq)
    }

    private fun encodePayload(
        events: List<ChunkEvent>,
        protIndex: IntArray,
        protKeys: Map<Long, Int>,
        firstMs: Long,
        firstTick: Int,
    ): ByteArray {
        val dt = ByteArrayOutputStream(events.size)
        dt.writeUVarInt(firstMs)
        var previousMs = firstMs
        for (event in events) {
            val delta = event.epochMs - previousMs
            if (delta < 0) throw ChunkFormatException("events must be in non-decreasing time order")
            dt.writeUVarInt(delta)
            previousMs = event.epochMs
        }

        val prot = ByteArrayOutputStream(events.size)
        for (index in protIndex) prot.writeUVarInt(index.toLong())

        val protDict = ByteArrayOutputStream(protKeys.size * 3)
        protDict.writeUVarInt(protKeys.size.toLong())
        for (key in protKeys.keys) {
            protDict.writeUVarInt((key ushr 32) and 0xFF)
            protDict.writeUVarInt(key and 0xFFFFFFFFL)
        }

        val sizes = ByteArrayOutputStream(events.size)
        for (event in events) sizes.writeUVarInt(event.body.size.toLong())

        val ticks = ByteArrayOutputStream(events.size)
        ticks.writeUVarInt(firstTick.toLong())
        var previousTick = firstTick
        for (event in events) {
            val delta = event.gameTick - previousTick
            if (delta < 0) throw ChunkFormatException("events must be in non-decreasing tick order")
            ticks.writeUVarInt(delta.toLong())
            previousTick = event.gameTick
        }

        val bodies = ByteArrayOutputStream(events.sumOf { it.body.size })
        for (group in 0 until protKeys.size) {
            for ((i, event) in events.withIndex()) {
                if (protIndex[i] == group) bodies.write(event.body)
            }
        }

        val flagged = events.withIndex().filter { it.value.quality != Quality.OK }
        val qflag = ByteArrayOutputStream(flagged.size * 2)
        if (flagged.isNotEmpty()) {
            qflag.writeUVarInt(flagged.size.toLong())
            var previousIndex = 0
            for ((index, event) in flagged) {
                qflag.writeUVarInt((index - previousIndex).toLong())
                qflag.write(event.quality.code)
                previousIndex = index
            }
        }

        val sections = buildList {
            add(SID_PROTDICT to protDict.toByteArray())
            add(SID_DT to dt.toByteArray())
            add(SID_PROT to prot.toByteArray())
            add(SID_SIZE to sizes.toByteArray())
            add(SID_TICK to ticks.toByteArray())
            if (flagged.isNotEmpty()) add(SID_QFLAG to qflag.toByteArray())
            add(SID_BODY to bodies.toByteArray())
        }

        val out = ByteArrayOutputStream()
        out.write(sections.size)
        for ((sid, bytes) in sections) {
            out.write(sid)
            out.writeUVarInt(bytes.size.toLong())
        }
        for ((_, bytes) in sections) out.write(bytes)
        return out.toByteArray()
    }

    private fun decodePayload(payload: ByteArray, count: Int, firstSeq: Long): List<ChunkEvent> {
        val directory = VarIntReader(payload, 1, payload.size)
        val sectionCount = payload[0].toInt() and 0xFF
        val ids = IntArray(sectionCount)
        val lengths = IntArray(sectionCount)
        for (i in 0 until sectionCount) {
            ids[i] = directory.readUVarInt().toInt()
            lengths[i] = directory.readUVarInt().toInt()
        }

        var cursor = directory.offset
        val offsets = HashMap<Int, Pair<Int, Int>>(sectionCount)
        for (i in 0 until sectionCount) {
            offsets[ids[i]] = cursor to lengths[i]
            cursor += lengths[i]
        }

        fun reader(sid: Int): VarIntReader {
            val (offset, length) = offsets[sid] ?: throw ChunkFormatException("missing section $sid")
            return VarIntReader(payload, offset, offset + length)
        }

        val protDict = reader(SID_PROTDICT)
        val protCount = protDict.readUVarInt().toInt()
        val protDirs = IntArray(protCount)
        val protOpcodes = IntArray(protCount)
        for (i in 0 until protCount) {
            protDirs[i] = protDict.readUVarInt().toInt()
            protOpcodes[i] = protDict.readUVarInt().toInt()
        }

        val dtReader = reader(SID_DT)
        val protReader = reader(SID_PROT)
        val sizeReader = reader(SID_SIZE)
        val tickReader = reader(SID_TICK)

        val protIndex = IntArray(count)
        val sizes = IntArray(count)
        val times = LongArray(count)
        val ticks = IntArray(count)
        var ms = dtReader.readUVarInt()
        var tick = tickReader.readUVarInt().toInt()
        for (i in 0 until count) {
            ms += dtReader.readUVarInt()
            times[i] = ms
            protIndex[i] = protReader.readUVarInt().toInt()
            sizes[i] = sizeReader.readUVarInt().toInt()
            tick += tickReader.readUVarInt().toInt()
            ticks[i] = tick
        }

        val qualities = Array(count) { Quality.OK }
        offsets[SID_QFLAG]?.let { (offset, length) ->
            val qflag = VarIntReader(payload, offset, offset + length)
            val flaggedCount = qflag.readUVarInt().toInt()
            var index = 0
            repeat(flaggedCount) {
                index += qflag.readUVarInt().toInt()
                val code = qflag.readUVarInt().toInt()
                qualities[index] = Quality.entries.firstOrNull { it.code == code }
                    ?: throw ChunkFormatException("unknown quality code $code")
            }
        }

        val (bodyOffset, _) = offsets[SID_BODY] ?: throw ChunkFormatException("missing body section")
        val groupStart = IntArray(protCount)
        var running = bodyOffset
        for (group in 0 until protCount) {
            groupStart[group] = running
            for (i in 0 until count) if (protIndex[i] == group) running += sizes[i]
        }

        val cursors = groupStart.copyOf()
        return List(count) { i ->
            val group = protIndex[i]
            val start = cursors[group]
            cursors[group] += sizes[i]
            ChunkEvent(
                seq = firstSeq + i,
                epochMs = times[i],
                monoNs = 0L,
                gameTick = ticks[i],
                dir = protDirs[group],
                opcode = protOpcodes[group],
                quality = qualities[i],
                body = payload.copyOfRange(start, start + sizes[i]),
            )
        }
    }

    private fun buildFrame(
        compressed: ChunkCodec.Compressed,
        payload: ByteArray,
        events: List<ChunkEvent>,
        protCount: Int,
    ): ByteArray {
        var flags = FLAG_BODIES_PROT_GROUPED or FLAG_HAS_TICK
        if (events.any { it.quality != Quality.OK }) flags = flags or FLAG_HAS_QFLAG

        val frame = ByteArray(HEADER_SIZE + compressed.bytes.size)
        val header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0, MAGIC)
        frame[4] = FORMAT_VERSION.toByte()
        frame[5] = compressed.codec.code.toByte()
        header.putShort(6, flags.toShort())
        header.putInt(8, protCount)
        header.putInt(12, events.size)
        header.putInt(16, payload.size)
        header.putInt(20, compressed.bytes.size)
        header.putInt(24, CRC32C().apply { update(payload) }.value.toInt())
        compressed.props.copyInto(frame, 28, 0, minOf(5, compressed.props.size))
        header.putInt(36, CRC32C().apply { update(frame, 0, HEADER_SIZE - 4) }.value.toInt())
        compressed.bytes.copyInto(frame, HEADER_SIZE)
        return frame
    }

    private fun protStats(
        events: List<ChunkEvent>,
        protIndex: IntArray,
        protKeys: Map<Long, Int>,
    ): List<ChunkProtStat> {
        val counts = IntArray(protKeys.size)
        val bytes = LongArray(protKeys.size)
        val first = IntArray(protKeys.size) { Int.MAX_VALUE }
        val last = IntArray(protKeys.size) { Int.MIN_VALUE }
        for ((i, event) in events.withIndex()) {
            val group = protIndex[i]
            counts[group]++
            bytes[group] += event.body.size
            if (event.gameTick < first[group]) first[group] = event.gameTick
            if (event.gameTick > last[group]) last[group] = event.gameTick
        }
        return protKeys.entries.map { (key, group) ->
            ChunkProtStat(
                dir = (key ushr 32).toInt(),
                opcode = (key and 0xFFFFFFFFL).toInt(),
                localIndex = group,
                packetCount = counts[group],
                bodyBytes = bytes[group],
                firstTick = first[group],
                lastTick = last[group],
            )
        }
    }

}
