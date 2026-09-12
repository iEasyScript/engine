package org.projectx.packetlog

import java.io.File
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.store.PacketSchema.Codec
import org.projectx.packetlog.store.PacketSchema.Quality
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Measures the real codecs against a real capture, because the storage budget for this whole
 * subsystem rests on one number and a prediction is not a measurement.
 *
 * Reads a legacy text dump so it can run before the new writer exists. Point it at one with
 * `-Dprojectx.bakeoff.log=<path>`; without that property the test is skipped.
 */
class ChunkCodecBakeOff {

    @Test
    fun `report stored bytes per codec and chunk size`() {
        val path = System.getProperty("projectx.bakeoff.log")
        assumeTrue(path != null, "set -Dprojectx.bakeoff.log to run the bake-off")
        val file = File(path!!)
        assumeTrue(file.isFile, "no such capture: $path")

        val events = parse(file)
        println("bake-off over ${events.size} packets, ${events.sumOf { it.body.size.toLong() }} body bytes")

        for (chunkSize in listOf(4096, 8192, 16384)) {
            for (codec in Codec.entries) {
                var stored = 0L
                var plain = 0L
                val elapsed = System.nanoTime()
                for (start in events.indices step chunkSize) {
                    val sealed = ChunkFrame.seal(events.subList(start, minOf(start + chunkSize, events.size)), codec)
                    stored += sealed.frame.size
                    plain += sealed.plainBytes
                }
                val seconds = (System.nanoTime() - elapsed) / 1e9
                println(
                    "chunk=%6d %-12s stored=%7.2f MB  plain=%7.2f MB  seal=%5.1fs"
                        .format(chunkSize, codec.wireName, stored / 1e6, plain / 1e6, seconds)
                )
            }
        }
    }

    private fun parse(file: File): List<ChunkEvent> {
        val header = Regex("""^\[(\d\d):(\d\d):(\d\d)\.(\d\d\d)] ([SC])> \S+ \(op=(\d+), (\d+)B\)""")
        val events = ArrayList<ChunkEvent>(700_000)
        var pending: ChunkEvent? = null
        var seq = 0L
        var lastMs = 0L

        fun flush() {
            pending?.let { events.add(it) }
            pending = null
        }

        file.forEachLine { line ->
            val match = header.find(line)
            if (match != null) {
                flush()
                val (h, m, s, ms, dir, opcode, _) = match.destructured
                val stamp = ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
                lastMs = maxOf(lastMs, stamp)
                pending = ChunkEvent(
                    seq = seq++,
                    epochMs = lastMs,
                    monoNs = 0,
                    gameTick = (events.size / 28),
                    dir = if (dir == "S") 0 else 1,
                    opcode = opcode.toInt(),
                    quality = Quality.OK,
                    body = ByteArray(0),
                )
            } else if (line.startsWith("    hex:")) {
                val hex = line.removePrefix("    hex:").trim()
                val bytes = ByteArray(hex.length / 3 + 1)
                var index = 0
                for (token in hex.split(' ')) {
                    if (token.isNotEmpty()) bytes[index++] = token.toInt(16).toByte()
                }
                pending = pending?.let {
                    ChunkEvent(it.seq, it.epochMs, it.monoNs, it.gameTick, it.dir, it.opcode, it.quality, bytes.copyOf(index))
                }
            }
        }
        flush()
        return events
    }
}
