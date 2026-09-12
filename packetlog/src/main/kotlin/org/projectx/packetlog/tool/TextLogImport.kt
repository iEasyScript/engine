package org.projectx.packetlog.tool

import java.io.File
import org.projectx.core.net.prot.ProtRevisions
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.store.CapturePolicy
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.PacketSessionWriter
import org.projectx.packetlog.store.ProtTable
import org.projectx.packetlog.store.SessionMetadata

/**
 * Imports a legacy text dump into a packet database.
 *
 * The old format has no game tick, no monotonic clock and only a wall-clock time of day, so imported
 * sessions are marked with their own provenance rather than being passed off as captures - they are
 * usable for decoder work but are not equivalent evidence to a live recording.
 */
object TextLogImport {

    private val HEADER = Regex("""^\[(\d\d):(\d\d):(\d\d)\.(\d\d\d)] ([SC])> (\S+) \(op=(-?\d+), (\d+)B\)""")

    class Report(val packets: Int, val bodyBytes: Long, val sourceBytes: Long, val storedBytes: Long)

    /**
     * [keepInputEvents] is off by default: a historical dump has no training session attached, so its
     * mouse and keyboard bodies have no consumer. The events are still imported, in sequence, with
     * empty bodies.
     */
    fun import(
        source: File,
        target: File,
        profile: PacketSchema.ServerProfile,
        keepInputEvents: Boolean = false,
    ): Report {
        val revision = ProtRevisions.current
        val codec = ProtRevisions.currentCodec()
        val protTable = ProtTable.snapshot(revision, codec)
        val metadata = SessionMetadata(
            player = null,
            world = null,
            startedEpochMs = source.lastModified(),
            monoBaseNs = 0,
            revision = revision,
            protTableHash = ProtTable.hash(protTable),
            platform = "unknown",
            arch = "unknown",
            clientBuild = "unknown",
            engineBuild = "import",
            serverProfile = profile,
            peerHash = null,
            consentVersion = 0,
            uploadOptIn = false,
            provenance = "import_text_v0",
        )

        var packets = 0
        var bodyBytes = 0L
        PacketSessionWriter.open(target, metadata, protTable).use { writer ->
            val store = writer.store
            val inputEventOpcodes = protTable
                .filter { it.dir == PacketSchema.Direction.CLIENT_TO_SERVER.code &&
                    it.name in CapturePolicy.INPUT_EVENT_PROTS }
                .map { it.opcode }
                .toSet()
            var pendingHeader: Header? = null
            var lastMs = 0L
            var dayRollovers = 0L
            // The text format has no tick column, but it does record the packet that ends every
            // tick - so the tick axis is recoverable by counting those, which is what makes an
            // imported capture investigable rather than just readable.
            val tickEndOpcode = codec.serverProtInfo.entries
                .firstOrNull { it.value.name == "SERVER_TICK_END" }?.key
            var tick = 0

            fun emit(header: Header, body: ByteArray) {
                var mode = store.capturePolicy.modeFor(header.dir, header.opcode)
                if (mode == PacketSchema.CaptureMode.DROP) return
                if (!keepInputEvents &&
                    header.dir == PacketSchema.Direction.CLIENT_TO_SERVER.code &&
                    header.opcode in inputEventOpcodes
                ) {
                    mode = PacketSchema.CaptureMode.COUNT_ONLY
                }
                val kept = if (mode == PacketSchema.CaptureMode.COUNT_ONLY) ByteArray(0) else body
                val event = ChunkEvent(
                    seq = store.assignSequence(),
                    epochMs = header.msOfDay + dayRollovers,
                    monoNs = 0,
                    gameTick = tick,
                    dir = header.dir,
                    opcode = header.opcode,
                    quality = PacketSchema.Quality.OK,
                    body = kept,
                )
                store.beginFlush()
                writer.append(event)
                store.commitFlush()
                writer.pump(event.epochMs)
                packets++
                bodyBytes += kept.size
                if (header.dir == PacketSchema.Direction.SERVER_TO_CLIENT.code &&
                    header.opcode == tickEndOpcode
                ) {
                    tick++
                }
            }

            source.forEachLine { line ->
                val match = HEADER.find(line)
                if (match != null) {
                    pendingHeader?.let { emit(it, ByteArray(0)) }
                    val (h, m, s, ms, dir, _, opcode, _) = match.destructured
                    var stamp = ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
                    // The old format records a time of day, so a session crossing midnight would go
                    // backwards. Carry the day rather than dropping the tail.
                    if (stamp + dayRollovers < lastMs) dayRollovers += 86_400_000L
                    lastMs = stamp + dayRollovers
                    pendingHeader = Header(
                        msOfDay = stamp,
                        dir = if (dir == "S") PacketSchema.Direction.SERVER_TO_CLIENT.code
                        else PacketSchema.Direction.CLIENT_TO_SERVER.code,
                        opcode = opcode.toInt().coerceAtLeast(0),
                    )
                } else if (line.startsWith("    hex:")) {
                    val header = pendingHeader ?: return@forEachLine
                    pendingHeader = null
                    emit(header, parseHex(line.removePrefix("    hex:").trim()))
                }
            }
            pendingHeader?.let { emit(it, ByteArray(0)) }
            writer.flush()
            store.closeSession(lastMs, "imported")
            store.close()
        }

        return Report(packets, bodyBytes, source.length(), target.length())
    }

    private class Header(val msOfDay: Long, val dir: Int, val opcode: Int)

    private fun parseHex(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        val tokens = hex.split(' ').filter { it.isNotEmpty() }
        val bytes = ByteArray(tokens.size)
        for ((i, token) in tokens.withIndex()) bytes[i] = token.toInt(16).toByte()
        return bytes
    }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("usage: textLogImport <source.log> <target.db> [live|local|unknown] [--keep-input-events]")
        return
    }
    val profile = PacketSchema.ServerProfile.entries
        .firstOrNull { it.wireName == (args.getOrNull(2) ?: "unknown") }
        ?: PacketSchema.ServerProfile.UNKNOWN

    val report = TextLogImport.import(
        File(args[0]),
        File(args[1]),
        profile,
        keepInputEvents = args.contains("--keep-input-events"),
    )
    println(
        "imported %d packets (%.2f MB of bodies) from %.2f MB of text into %.2f MB of database (%.1fx smaller)"
            .format(
                report.packets,
                report.bodyBytes / 1e6,
                report.sourceBytes / 1e6,
                report.storedBytes / 1e6,
                report.sourceBytes.toDouble() / report.storedBytes.coerceAtLeast(1),
            )
    )
}
