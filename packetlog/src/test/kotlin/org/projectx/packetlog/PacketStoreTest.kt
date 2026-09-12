package org.projectx.packetlog

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import org.projectx.core.sqlite.SqliteDriver
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.store.PacketSessionWriter
import org.projectx.packetlog.store.PacketStore
import org.projectx.packetlog.store.ProtEntry
import org.projectx.packetlog.store.SessionMetadata
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketStoreTest {

    private val protTable = listOf(
        ProtEntry(949, 0, 28, "UPDATE_ZONE_FULL_FOLLOWS", 3),
        ProtEntry(949, 0, 45, "PLAYER_INFO", -2),
        ProtEntry(949, 0, 180, "SERVER_TICK_END", 0),
        ProtEntry(949, 1, 0, "NO_TIMEOUT", 0),
    )

    private fun metadata() = SessionMetadata(
        player = "tester",
        world = 1,
        startedEpochMs = 1_700_000_000_000L,
        monoBaseNs = 0,
        revision = 949,
        protTableHash = ByteArray(32),
        platform = "linux",
        arch = "x86_64",
        clientBuild = "949-5",
        engineBuild = "test",
        serverProfile = PacketSchema.ServerProfile.LOCAL,
        peerHash = null,
        consentVersion = 0,
        uploadOptIn = false,
        provenance = "test_v1",
    )

    private fun events(store: PacketStore, count: Int, tickBase: Int = 0) = List(count) { i ->
        ChunkEvent(
            seq = store.assignSequence(),
            epochMs = 1_700_000_000_000L + i,
            monoNs = i * 1_000_000L,
            gameTick = tickBase + i / 10,
            dir = 0,
            opcode = listOf(28, 45, 180)[i % 3],
            quality = PacketSchema.Quality.OK,
            body = ByteArray(i % 7) { it.toByte() },
        )
    }

    private fun tempFile(name: String): File =
        File(Files.createTempDirectory("packetlog").toFile(), name)

    @Test
    fun `chunk commit clears the rows it supersedes in one step`() {
        val file = tempFile("archive.db")
        PacketStore.open(file, metadata(), protTable).use { store ->
            val batch = events(store, 50)
            store.beginFlush()
            batch.forEach { store.addPending(it) }
            store.commitFlush()

            assertEquals(50, store.readPending().size, "spilled before sealing")

            store.commitChunk(949, ChunkFrame.seal(batch))
            assertEquals(0, store.readPending().size, "spill cleared by the commit")
            assertEquals(1L, store.nextOrdinal)
        }

        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count, body_bytes, frame FROM chunk").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(50, rows.getInt(1))
                    val read = ChunkFrame.open(rows.getBytes(3))
                    assertEquals(50, read.size)
                }
                statement.executeQuery("SELECT count(*) FROM chunk_prot").use { rows ->
                    rows.next()
                    assertEquals(3, rows.getInt(1), "one row per distinct prot in the chunk")
                }
                statement.executeQuery("SELECT packet_count FROM session").use { rows ->
                    rows.next()
                    assertEquals(50, rows.getInt(1))
                }
            }
        }
    }

    /** A session is a row. The archive must accumulate them, never restart. */
    @Test
    fun `sessions append across opens`() {
        val file = tempFile("archive.db")
        repeat(3) {
            PacketStore.open(file, metadata(), protTable).use { store ->
                val batch = events(store, 12)
                store.beginFlush()
                batch.forEach { store.addPending(it) }
                store.commitFlush()
                store.commitChunk(949, ChunkFrame.seal(batch))
                store.closeSession(1_700_000_100_000L, "clean")
            }
        }

        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM session").use { rows ->
                    rows.next()
                    assertEquals(3, rows.getInt(1))
                }
                statement.executeQuery("SELECT count(DISTINCT uuid) FROM session").use { rows ->
                    rows.next()
                    assertEquals(3, rows.getInt(1), "each session gets its own uuid")
                }
                statement.executeQuery("SELECT count(*) FROM chunk").use { rows ->
                    rows.next()
                    assertEquals(3, rows.getInt(1))
                }
            }
        }
    }

    /** Everything up to the last flush is spilled, so a kill before sealing loses nothing. */
    @Test
    fun `unsealed rows survive to be re-sealed`() {
        val file = tempFile("capture.db")
        val bodies: List<ByteArray>
        PacketStore.open(file, metadata(), protTable).use { store ->
            val batch = events(store, 30)
            bodies = batch.map { it.body }
            store.beginFlush()
            batch.forEach { store.addPending(it) }
            store.commitFlush()
        }

        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use { connection ->
            connection.prepareStatement("SELECT seq, body FROM pending ORDER BY seq").use { statement ->
                statement.executeQuery().use { rows ->
                    var index = 0
                    while (rows.next()) {
                        assertArrayEquals(bodies[index], rows.getBytes(2) ?: ByteArray(0), "body $index")
                        index++
                    }
                    assertEquals(30, index)
                }
            }
        }
    }

    @Test
    fun `chunk_prot answers a prot and tick filter without opening a frame`() {
        val file = tempFile("archive.db")
        PacketStore.open(file, metadata(), protTable).use { store ->
            val batch = events(store, 300)
            store.beginFlush()
            batch.forEach { store.addPending(it) }
            store.commitFlush()
            store.commitChunk(949, ChunkFrame.seal(batch))
        }

        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use { connection ->
            connection.prepareStatement(
                """
                SELECT c.ordinal, cp.packet_count
                FROM chunk c
                JOIN chunk_prot cp ON cp.chunk_id = c.id
                JOIN prot p ON p.id = cp.prot_id
                WHERE p.name = ? AND cp.first_tick <= ? AND cp.last_tick >= ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "PLAYER_INFO")
                statement.setInt(2, 20)
                statement.setInt(3, 5)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next(), "the pruning query locates the chunk")
                    assertEquals(100, rows.getInt(2))
                }
            }
        }
    }

    /**
     * A wall clock that steps backwards mid-session (an NTP correction) must not be able to produce
     * a batch that can never seal - that would cost real packets to protect a millisecond of timing.
     */
    @Test
    fun `a backwards clock is pinned forward instead of poisoning the chunk`() {
        val file = tempFile("archive.db")
        PacketStore.open(file, metadata(), protTable).use { store ->
            PacketSessionWriter(store, 949, PacketSessionWriter.SealPolicy(maxEvents = 1000)).use { writer ->
                val times = listOf(1_000L, 1_010L, 1_005L, 1_002L, 1_030L)
                store.beginFlush()
                for ((i, ms) in times.withIndex()) {
                    writer.append(
                        ChunkEvent(store.assignSequence(), ms, 0, i / 2, 0, 45,
                            PacketSchema.Quality.OK, byteArrayOf(i.toByte()))
                    )
                }
                store.commitFlush()
                writer.flush()

                assertEquals(2L, writer.clampedTimestamps, "both backwards readings counted")
                assertEquals(0L, writer.sealFailures, "the chunk still sealed")
            }
        }

        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT first_seq, count, frame FROM chunk").use { rows ->
                    assertTrue(rows.next(), "a chunk was written")
                    assertEquals(5, rows.getInt(2))
                    val read = ChunkFrame.open(rows.getBytes(3), rows.getLong(1))
                    // Bodies survive untouched; only the clock readings were pinned.
                    assertEquals(listOf<Byte>(0, 1, 2, 3, 4), read.map { it.body.single() })
                    assertEquals(listOf(1_000L, 1_010L, 1_010L, 1_010L, 1_030L), read.map { it.epochMs })
                }
            }
        }
    }
}
