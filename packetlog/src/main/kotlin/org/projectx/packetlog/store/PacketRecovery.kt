package org.projectx.packetlog.store

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.projectx.core.sqlite.SqliteDriver
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.chunk.SealedChunk

/**
 * Closes out sessions a previous run left open.
 *
 * The client is killed outright often enough that clean shutdown is the optimisation and this is the
 * primary path. Everything up to the last flush is in the spill table, so recovery re-seals it into
 * the session it belongs to rather than discarding it, and marks the session `recovered` so nobody
 * reads its tail as complete.
 */
object PacketRecovery {

    class Result(val sessions: Int, val resealed: Int)

    fun recover(file: File): Result {
        if (!file.isFile) return Result(0, 0)
        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=rwc").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            var sessions = 0
            var resealed = 0
            for (sessionId in unclosedSessions(connection)) {
                resealed += resealSession(connection, sessionId)
                sessions++
            }
            return Result(sessions, resealed)
        }
    }

    private fun unclosedSessions(connection: Connection): List<Long> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM session WHERE ended_epoch_ms IS NULL ORDER BY id").use { rows ->
                buildList { while (rows.next()) add(rows.getLong(1)) }
            }
        }

    private fun resealSession(connection: Connection, sessionId: Long): Int {
        val revision = connection.prepareStatement("SELECT revision FROM session WHERE id = ?").use {
            it.setLong(1, sessionId)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        val spilled = readPending(connection, sessionId)
        var ordinal = nextOrdinal(connection, sessionId)

        if (spilled.isNotEmpty()) {
            connection.autoCommit = false
            try {
                val sealed = ChunkFrame.seal(spilled)
                insertChunk(connection, sessionId, ordinal++, revision, sealed)
                connection.prepareStatement("DELETE FROM pending WHERE session_id = ?").use {
                    it.setLong(1, sessionId)
                    it.executeUpdate()
                }
                connection.commit()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }

        val endedMs = connection.prepareStatement(
            "SELECT COALESCE(MAX(last_ms), started_epoch_ms) FROM chunk, session WHERE session.id = ? AND chunk.session_id = ?"
        ).use {
            it.setLong(1, sessionId)
            it.setLong(2, sessionId)
            it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
        connection.prepareStatement(
            "UPDATE session SET ended_epoch_ms = ?, close_reason = 'recovered' WHERE id = ?"
        ).use {
            it.setLong(1, endedMs)
            it.setLong(2, sessionId)
            it.executeUpdate()
        }
        return spilled.size
    }

    private fun readPending(connection: Connection, sessionId: Long): List<ChunkEvent> =
        connection.prepareStatement(
            """
            SELECT seq, epoch_ms, mono_ns, game_tick, dir, opcode, quality, body
            FROM pending WHERE session_id = ? ORDER BY seq
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val code = rows.getInt(7)
                        add(
                            ChunkEvent(
                                seq = rows.getLong(1),
                                epochMs = rows.getLong(2),
                                monoNs = rows.getLong(3),
                                gameTick = rows.getInt(4),
                                dir = rows.getInt(5),
                                opcode = rows.getInt(6),
                                quality = PacketSchema.Quality.entries.first { it.code == code },
                                body = rows.getBytes(8) ?: ByteArray(0),
                            )
                        )
                    }
                }
            }
        }

    private fun nextOrdinal(connection: Connection, sessionId: Long): Long =
        connection.prepareStatement("SELECT COALESCE(MAX(ordinal) + 1, 0) FROM chunk WHERE session_id = ?").use {
            it.setLong(1, sessionId)
            it.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }

    private fun insertChunk(
        connection: Connection,
        sessionId: Long,
        ordinal: Long,
        revision: Int,
        sealed: SealedChunk,
    ) {
        val chunkId = connection.prepareStatement(
            """
            INSERT INTO chunk(session_id, ordinal, state, first_seq, count, first_ms, last_ms,
                              first_mono_ns, first_tick, last_tick, body_bytes, plain_bytes,
                              stored_bytes, codec, plain_sha256, sealed_ms, frame)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, sessionId)
            statement.setLong(2, ordinal)
            statement.setInt(3, PacketSchema.ChunkState.SEALED.code)
            statement.setLong(4, sealed.firstSeq)
            statement.setInt(5, sealed.count)
            statement.setLong(6, sealed.firstMs)
            statement.setLong(7, sealed.lastMs)
            statement.setLong(8, sealed.firstMonoNs)
            statement.setInt(9, sealed.firstTick)
            statement.setInt(10, sealed.lastTick)
            statement.setLong(11, sealed.bodyBytes)
            statement.setInt(12, sealed.plainBytes)
            statement.setInt(13, sealed.frame.size)
            statement.setInt(14, sealed.codec.code)
            statement.setBytes(15, sealed.plainSha256)
            statement.setLong(16, System.currentTimeMillis())
            statement.setBytes(17, sealed.frame)
            statement.executeUpdate()
            connection.createStatement().use { s ->
                s.executeQuery("SELECT last_insert_rowid()").use { rows -> rows.next(); rows.getLong(1) }
            }
        }

        connection.prepareStatement(
            """
            INSERT INTO chunk_prot(chunk_id, prot_id, local_index, packet_count, body_bytes, first_tick, last_tick)
            SELECT ?, p.id, ?, ?, ?, ?, ?
            FROM prot p WHERE p.revision = ? AND p.dir = ? AND p.opcode = ?
            """.trimIndent()
        ).use { statement ->
            for (stat in sealed.protStats) {
                statement.setLong(1, chunkId)
                statement.setInt(2, stat.localIndex)
                statement.setInt(3, stat.packetCount)
                statement.setLong(4, stat.bodyBytes)
                statement.setInt(5, stat.firstTick)
                statement.setInt(6, stat.lastTick)
                statement.setInt(7, revision)
                statement.setInt(8, stat.dir)
                statement.setInt(9, stat.opcode)
                statement.addBatch()
            }
            statement.executeBatch()
        }

        connection.prepareStatement(
            "UPDATE session SET packet_count = packet_count + ?, body_bytes = body_bytes + ? WHERE id = ?"
        ).use {
            it.setInt(1, sealed.count)
            it.setLong(2, sealed.bodyBytes)
            it.setLong(3, sessionId)
            it.executeUpdate()
        }
    }
}
