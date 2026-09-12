package org.projectx.packetlog

import kotlin.random.Random
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.store.PacketSchema.Codec
import org.projectx.packetlog.store.PacketSchema.Quality
import org.junit.jupiter.api.Assertions.assertArrayEquals
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChunkFrameTest {

    private fun assertRoundTrips(events: List<ChunkEvent>, codec: Codec) {
        val sealed = ChunkFrame.seal(events, codec)
        val read = ChunkFrame.open(sealed.frame, sealed.firstSeq)

        assertEquals(events.size, read.size, "event count")
        for ((expected, actual) in events.zip(read)) {
            assertEquals(expected.seq, actual.seq, "seq")
            assertEquals(expected.epochMs, actual.epochMs, "epochMs")
            assertEquals(expected.gameTick, actual.gameTick, "gameTick")
            assertEquals(expected.dir, actual.dir, "dir")
            assertEquals(expected.opcode, actual.opcode, "opcode")
            assertEquals(expected.quality, actual.quality, "quality")
            assertArrayEquals(expected.body, actual.body, "body of seq ${expected.seq}")
        }
    }

    /** The reordering is the risky part: bodies are grouped by prot but must read back interleaved. */
    @Test
    fun `interleaved prots survive prot-grouped body ordering`() {
        val events = List(500) { i ->
            val opcode = i % 7
            ChunkEvent(
                seq = 1000L + i,
                epochMs = 1_700_000_000_000L + i * 3,
                monoNs = i * 3_000_000L,
                gameTick = i / 9,
                dir = i % 2,
                opcode = opcode,
                quality = Quality.OK,
                body = ByteArray(opcode + 1) { (i + it).toByte() },
            )
        }
        for (codec in Codec.entries) assertRoundTrips(events, codec)
    }

    /** Byte-identical repeats in one millisecond are distinct events and must keep their order. */
    @Test
    fun `identical bodies in the same millisecond stay distinct and ordered`() {
        val body = byteArrayOf(0x01, 0x02, 0x03)
        val events = List(64) { i ->
            ChunkEvent(
                seq = i.toLong(),
                epochMs = 1_700_000_000_000L,
                monoNs = 0,
                gameTick = 0,
                dir = 0,
                opcode = if (i % 3 == 0) 28 else 45,
                quality = Quality.OK,
                body = if (i % 3 == 0) body else byteArrayOf(i.toByte()),
            )
        }
        assertRoundTrips(events, Codec.LZMA1_RAW)
        assertEquals(64, ChunkFrame.open(ChunkFrame.seal(events).frame).size)
    }

    @Test
    fun `zero length bodies and flagged qualities round trip`() {
        val events = listOf(
            ChunkEvent(0, 5L, 0, 0, 0, 180, Quality.OK, ByteArray(0)),
            ChunkEvent(1, 5L, 0, 0, 0, 93, Quality.DESYNC_WITHHELD, ByteArray(0)),
            ChunkEvent(2, 7L, 0, 1, 1, 0, Quality.DROPPED_OVERFLOW, ByteArray(0)),
            ChunkEvent(3, 9L, 0, 1, 0, 45, Quality.OK, byteArrayOf(9, 8, 7)),
        )
        for (codec in Codec.entries) assertRoundTrips(events, codec)
    }

    @Test
    fun `random streams round trip`() {
        val random = Random(20260825)
        var clock = 1_700_000_000_000L
        val events = List(2000) { i ->
            clock += random.nextInt(0, 4)
            ChunkEvent(
                seq = i.toLong(),
                epochMs = clock,
                monoNs = 0,
                gameTick = i / 12,
                dir = random.nextInt(0, 2),
                opcode = random.nextInt(0, 230),
                quality = Quality.OK,
                body = random.nextBytes(random.nextInt(0, 300)),
            )
        }
        for (codec in Codec.entries) assertRoundTrips(events, codec)
    }

    @Test
    fun `prot stats account for every packet and byte`() {
        val events = List(300) { i ->
            ChunkEvent(i.toLong(), i.toLong(), 0, i / 10, 0, i % 5, Quality.OK, ByteArray(i % 5))
        }
        val sealed = ChunkFrame.seal(events)
        assertEquals(events.size, sealed.protStats.sumOf { it.packetCount })
        assertEquals(events.sumOf { it.body.size.toLong() }, sealed.protStats.sumOf { it.bodyBytes })
        assertEquals(sealed.bodyBytes, sealed.protStats.sumOf { it.bodyBytes })
    }

    /**
     * Pins the container bytes. Kotlin, Python and the ingest server each implement this format, and
     * a fixed vector is the only thing that catches one of them drifting. Regenerate deliberately
     * with -Dprojectx.regeneratePacketVector=true, and only alongside a format version bump.
     */
    @Test
    fun `the container layout matches its golden vector`() {
        val events = listOf(
            ChunkEvent(0, 1_700_000_000_000L, 0, 10, 0, 28, Quality.OK, byteArrayOf(1, 2, 3)),
            ChunkEvent(1, 1_700_000_000_005L, 0, 10, 0, 45, Quality.OK, byteArrayOf(9)),
            ChunkEvent(2, 1_700_000_000_005L, 0, 11, 1, 0, Quality.DESYNC_WITHHELD, ByteArray(0)),
            ChunkEvent(3, 1_700_000_000_020L, 0, 12, 0, 28, Quality.OK, byteArrayOf(1, 2, 3)),
        )
        // STORE keeps the payload readable, so the vector pins the container rather than a codec.
        val sealed = ChunkFrame.seal(events, Codec.STORE)
        val actual = sealed.frame.joinToString("") { "%02x".format(it) }

        val vector = File("src/test/resources/packet-chunk-v1.hex")
        if (System.getProperty("projectx.regeneratePacketVector") == "true") {
            vector.parentFile.mkdirs()
            vector.writeText(actual + "\n")
        }
        assertTrue(vector.isFile, "golden vector missing; regenerate with -Dprojectx.regeneratePacketVector=true")
        assertEquals(vector.readText().trim(), actual, "chunk container bytes changed")

        // The vector is only meaningful if it still decodes to what produced it.
        val read = ChunkFrame.open(sealed.frame)
        assertEquals(events.size, read.size)
        assertArrayEquals(events[3].body, read[3].body)
        assertEquals(Quality.DESYNC_WITHHELD, read[2].quality)
    }
}
