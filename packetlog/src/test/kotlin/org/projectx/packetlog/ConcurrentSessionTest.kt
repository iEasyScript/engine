package org.projectx.packetlog

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.projectx.core.sqlite.SqliteDriver
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.store.PacketRecovery
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.PacketSessionWriter
import org.projectx.packetlog.store.PacketStore
import org.projectx.packetlog.store.ProtEntry
import org.projectx.packetlog.store.SessionFiles
import org.projectx.packetlog.store.SessionMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Two clients on one machine is ordinary - two accounts, or one account on live and one on a local
 * server - so nothing here may depend on there being a single writer.
 */
class ConcurrentSessionTest {

    private val protTable = listOf(
        ProtEntry(949, 0, 45, "PLAYER_INFO", -2),
        ProtEntry(949, 0, 28, "UPDATE_ZONE_FULL_FOLLOWS", 3),
    )

    private fun metadata(player: String) = SessionMetadata(
        player = player, world = 1, startedEpochMs = 1_700_000_000_000L, monoBaseNs = 0,
        revision = 949, protTableHash = ByteArray(32), platform = "linux", arch = "x86_64",
        clientBuild = "949-5", engineBuild = "test",
        serverProfile = PacketSchema.ServerProfile.LIVE, peerHash = null,
        consentVersion = 1, uploadOptIn = true, provenance = "test_v1",
    )

    private fun directory(): File = Files.createTempDirectory("packetlog-concurrent").toFile()

    private fun writeSession(directory: File, player: String, packets: Int, holdOpen: CountDownLatch? = null) {
        val owned = SessionFiles.claim(directory)!!
        val store = PacketStore.open(owned.file, metadata(player), protTable, owned.uuid)
        val writer = PacketSessionWriter(store, 949, PacketSessionWriter.SealPolicy(maxEvents = 50))
        store.beginFlush()
        repeat(packets) { i ->
            writer.append(
                ChunkEvent(store.assignSequence(), 1_700_000_000_000L + i, 0, i / 5, 0, 45,
                    PacketSchema.Quality.OK, ByteArray(4) { player[0].code.toByte() })
            )
        }
        store.commitFlush()
        writer.flush()
        holdOpen?.await(10, TimeUnit.SECONDS)
        store.closeSession(1_700_000_010_000L, "clean")
        writer.close()
        store.close()
        owned.close()
    }

    /** Each session gets its own file, so there is never more than one writer per database. */
    @Test
    fun `two clients capturing at once never share a database`() {
        val directory = directory()
        val ready = CountDownLatch(2)
        val threads = listOf("alice", "bob").map { player ->
            thread {
                ready.countDown()
                ready.await(10, TimeUnit.SECONDS)
                writeSession(directory, player, packets = 120)
            }
        }
        threads.forEach { it.join(30_000) }

        val files = SessionFiles.all(directory)
        assertEquals(2, files.size, "one database per session")
        assertEquals(2, files.map { it.name }.toSet().size, "and their names are distinct")

        SqliteDriver.register()
        val players = files.map { file ->
            DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT player, packet_count FROM session").use { rows ->
                        rows.next()
                        assertEquals(120, rows.getInt(2), "every packet of this session landed")
                        rows.getString(1)
                    }
                }
            }
        }.toSet()
        assertEquals(setOf("alice", "bob"), players, "neither session absorbed the other's packets")
    }

    /**
     * The bug this design exists to prevent: one client's crash recovery must not close out
     * another client's still-running session.
     */
    @Test
    fun `recovery never touches a session another client still owns`() {
        val directory = directory()
        val live = SessionFiles.claim(directory)!!
        val store = PacketStore.open(live.file, metadata("live-client"), protTable, live.uuid)
        store.beginFlush()
        store.addPending(
            ChunkEvent(store.assignSequence(), 1L, 0, 0, 0, 45, PacketSchema.Quality.OK, byteArrayOf(1))
        )
        store.commitFlush()

        // A second client starting up sees the directory and tries to finish what it finds.
        assertTrue(SessionFiles.abandoned(directory).isEmpty(), "a held session is not abandoned")
        for (file in SessionFiles.abandoned(directory)) PacketRecovery.recover(file)

        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${live.file.absolutePath}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT ended_epoch_ms, close_reason FROM session").use { rows ->
                    rows.next()
                    assertEquals(0L, rows.getLong(1), "the live session was not closed out")
                    assertTrue(rows.wasNull() || rows.getLong(1) == 0L)
                    assertNull(rows.getString(2), "and was not marked recovered")
                }
                statement.executeQuery("SELECT count(*) FROM pending").use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1), "its spill was not stolen and re-sealed")
                }
            }
        }
        store.close()
        live.close()
    }

    /** Once the owner is gone the file becomes recoverable, which is how a killed client is finished. */
    @Test
    fun `a released session becomes recoverable`() {
        val directory = directory()
        val owned = SessionFiles.claim(directory)!!
        val store = PacketStore.open(owned.file, metadata("crashed"), protTable, owned.uuid)
        store.beginFlush()
        repeat(10) { i ->
            store.addPending(
                ChunkEvent(store.assignSequence(), i.toLong(), 0, 0, 0, 45, PacketSchema.Quality.OK, byteArrayOf(1))
            )
        }
        store.commitFlush()
        store.close()
        owned.close()

        val abandoned = SessionFiles.abandoned(directory)
        assertEquals(1, abandoned.size, "an unheld session is recoverable")
        val result = PacketRecovery.recover(abandoned.single())
        assertEquals(1, result.sessions)
        assertEquals(10, result.resealed, "its spill was finished rather than lost")
    }

    @Test
    fun `a claim on a held session file is refused rather than shared`() {
        val directory = directory()
        val first = SessionFiles.claim(directory)!!
        assertNotNull(first)
        val again = SessionFiles.claim(directory, first.uuid)
        assertNull(again, "the same session file cannot be claimed twice")
        first.close()
        assertNotNull(SessionFiles.claim(directory, first.uuid), "and can be claimed once released")
    }

    @Test
    fun `distinct sessions do not collide on identity`() {
        val directory = directory()
        val a = SessionFiles.claim(directory)!!
        val b = SessionFiles.claim(directory)!!
        assertFalse(a.uuid == b.uuid, "each session has its own identity")
        assertFalse(a.file == b.file, "and its own file")
        a.close()
        b.close()
    }

    /**
     * The case that actually happens: the client is closed, so nothing runs engine teardown. The
     * session must still end up finished rather than sitting open forever.
     */
    @Test
    fun `a session left unfinished by a departed client is closed and its tail sealed`() {
        val directory = directory()
        val owned = SessionFiles.claim(directory)!!
        val store = PacketStore.open(owned.file, metadata("gone"), protTable, owned.uuid)
        store.beginFlush()
        repeat(25) { i ->
            store.addPending(
                ChunkEvent(store.assignSequence(), i.toLong(), 0, i / 5, 0, 45,
                    PacketSchema.Quality.OK, byteArrayOf(i.toByte()))
            )
        }
        store.commitFlush()
        store.close()
        owned.close()

        for (file in SessionFiles.abandoned(directory)) PacketRecovery.recover(file)

        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${owned.file.absolutePath}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT ended_epoch_ms, close_reason, packet_count FROM session").use { rows ->
                    rows.next()
                    assertTrue(rows.getLong(1) > 0, "the session is stamped as ended")
                    assertEquals("recovered", rows.getString(2))
                    assertEquals(25, rows.getInt(3), "and its unsealed tail was sealed, not dropped")
                }
                statement.executeQuery("SELECT count(*) FROM pending").use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }
}
