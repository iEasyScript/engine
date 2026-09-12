package org.projectx.packetlog

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import org.projectx.core.sqlite.SqliteDriver
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.store.PacketRecovery
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.PacketStore
import org.projectx.packetlog.store.PacketSessionWriter
import org.projectx.packetlog.store.ProtEntry
import org.projectx.packetlog.store.SessionMetadata
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PacketRecoveryTest {

    private val protTable = listOf(
        ProtEntry(949, 0, 28, "UPDATE_ZONE_FULL_FOLLOWS", 3),
        ProtEntry(949, 0, 45, "PLAYER_INFO", -2),
        ProtEntry(949, 0, 180, "SERVER_TICK_END", 0),
    )

    private fun metadata() = SessionMetadata(
        player = "tester", world = 1, startedEpochMs = 1_700_000_000_000L, monoBaseNs = 0,
        revision = 949, protTableHash = ByteArray(32), platform = "linux", arch = "x86_64",
        clientBuild = "949-5", engineBuild = "test",
        serverProfile = PacketSchema.ServerProfile.LOCAL, peerHash = null,
        consentVersion = 0, uploadOptIn = false, provenance = "test_v1",
    )

    private fun tempFile(): File = File(Files.createTempDirectory("packetlog-recovery").toFile(), "capture.db")

    /**
     * Simulates the kill this system is actually built for: the writer is abandoned mid-chunk with
     * no clean close, exactly as a SIGKILL leaves it.
     */
    @Test
    fun `a kill mid-chunk loses nothing`() {
        val file = tempFile()
        val bodies = ArrayList<ByteArray>()

        val store = PacketStore.open(file, metadata(), protTable)
        val writer = PacketSessionWriter(store, 949, PacketSessionWriter.SealPolicy(maxEvents = 100))
        // Two full chunks seal; the remaining 50 are still only in the spill table.
        repeat(250) { i ->
            val event = ChunkEvent(
                seq = store.assignSequence(),
                epochMs = 1_700_000_000_000L + i,
                monoNs = i * 1_000_000L,
                gameTick = i / 10,
                dir = 0,
                opcode = listOf(28, 45, 180)[i % 3],
                quality = PacketSchema.Quality.OK,
                body = ByteArray(1 + i % 11) { (i + it).toByte() },
            )
            bodies.add(event.body)
            store.beginFlush()
            writer.append(event)
            store.commitFlush()
            writer.pump(event.epochMs)
        }
        // Let the two full chunks land, then abandon: no flush, no close, no closeSession - the
        // process is simply gone, with the last 50 events still only in the spill table.
        repeat(200) {
            writer.pump(1_700_000_000_250L)
            if (writer.pendingSeals == 0) return@repeat
            Thread.sleep(5)
        }
        writer.pump(1_700_000_000_250L)

        val result = PacketRecovery.recover(file)
        assertEquals(1, result.sessions, "one crashed session closed out")
        assertEquals(50, result.resealed, "the unsealed tail was re-sealed, not discarded")

        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { rows ->
                    rows.next()
                    assertEquals("ok", rows.getString(1))
                }
                statement.executeQuery("SELECT close_reason, packet_count FROM session").use { rows ->
                    rows.next()
                    assertEquals("recovered", rows.getString(1))
                    assertEquals(250, rows.getInt(2), "every packet accounted for")
                }
                statement.executeQuery("SELECT count(*) FROM pending").use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1), "spill drained")
                }
            }

            // Every body, in order, across all chunks.
            val recovered = ArrayList<ByteArray>()
            connection.prepareStatement("SELECT first_seq, frame FROM chunk ORDER BY ordinal").use { statement ->
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        ChunkFrame.open(rows.getBytes(2), rows.getLong(1)).forEach { recovered.add(it.body) }
                    }
                }
            }
            assertEquals(bodies.size, recovered.size, "no packet lost")
            for (i in bodies.indices) assertArrayEquals(bodies[i], recovered[i], "body $i")
        }
    }

    /** Recovery must converge rather than duplicate when it runs twice. */
    @Test
    fun `recovery is idempotent`() {
        val file = tempFile()
        val store = PacketStore.open(file, metadata(), protTable)
        val writer = PacketSessionWriter(store, 949, PacketSessionWriter.SealPolicy(maxEvents = 1000))
        store.beginFlush()
        repeat(40) { i ->
            writer.append(
                ChunkEvent(store.assignSequence(), 1_700_000_000_000L + i, 0, i / 10, 0, 45,
                    PacketSchema.Quality.OK, ByteArray(3))
            )
        }
        store.commitFlush()

        val first = PacketRecovery.recover(file)
        val second = PacketRecovery.recover(file)
        assertEquals(40, first.resealed)
        assertEquals(0, second.resealed, "nothing left to reseal")
        assertEquals(0, second.sessions, "the session is already closed")

        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*), sum(count) FROM chunk").use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1), "exactly one chunk, not two")
                    assertEquals(40, rows.getInt(2))
                }
            }
        }
        assertNotNull(writer)
    }
}
