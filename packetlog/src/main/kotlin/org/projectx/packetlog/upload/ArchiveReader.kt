package org.projectx.packetlog.upload

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.projectx.core.sqlite.SqliteDriver
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.store.PacketSchema

/**
 * Read side of an archive, for export and upload.
 *
 * Opens read-only, always: the client may be running and writing to this file, and an accidental
 * read-write open would both take a lock and let a bug here damage the only copy of a capture.
 */
class ArchiveReader(private val file: File) : AutoCloseable {

    private val connection: Connection = run {
        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=ro")
    }

    class Session(
        val id: Long,
        val uuid: String,
        val kind: String,
        val revision: Int,
        val protTableHash: String,
        val clientBuild: String,
        val engineBuild: String,
        val platform: String,
        val arch: String,
        val startedEpochMs: Long,
        val endedEpochMs: Long?,
        val consentVersion: Int,
        val uploadOptIn: Boolean,
        val packetCount: Long,
    )

    class Chunk(val ordinal: Long, val firstSeq: Long, val count: Int, val frame: ByteArray)

    fun sessions(): List<Session> =
        connection.prepareStatement(
            """
            SELECT s.id, hex(s.uuid), p.name, s.revision, hex(s.prot_table_hash), s.client_build,
                   s.engine_build, s.platform, s.arch, s.started_epoch_ms, s.ended_epoch_ms,
                   s.consent_version, s.upload_opt_in, s.packet_count
            FROM session s JOIN server_profile p ON p.code = s.server_profile
            ORDER BY s.id
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            Session(
                                id = rows.getLong(1),
                                uuid = canonicalUuid(rows.getString(2)),
                                kind = rows.getString(3),
                                revision = rows.getInt(4),
                                protTableHash = rows.getString(5).lowercase(),
                                clientBuild = rows.getString(6),
                                engineBuild = rows.getString(7),
                                platform = rows.getString(8),
                                arch = rows.getString(9),
                                startedEpochMs = rows.getLong(10),
                                endedEpochMs = rows.getLong(11).takeIf { !rows.wasNull() },
                                consentVersion = rows.getInt(12),
                                uploadOptIn = rows.getInt(13) != 0,
                                packetCount = rows.getLong(14),
                            )
                        )
                    }
                }
            }
        }

    fun chunks(sessionId: Long): List<Chunk> =
        connection.prepareStatement(
            "SELECT ordinal, first_seq, count, frame FROM chunk WHERE session_id = ? ORDER BY ordinal"
        ).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(Chunk(rows.getLong(1), rows.getLong(2), rows.getInt(3), rows.getBytes(4)))
                    }
                }
            }
        }

    /**
     * How far along [session] is, without reading a single frame.
     *
     * Cheap by design: this runs on every heartbeat against a database another process is writing,
     * so it counts rows and reads timestamps rather than opening chunks.
     */
    fun heartbeatFor(session: Session): SessionHeartbeat {
        val (chunks, lastChunkMs) = countAndLatest(
            "SELECT COUNT(*), COALESCE(MAX(last_ms), 0) FROM chunk WHERE session_id = ?", session.id
        )
        val (pending, lastPendingMs) = countAndLatest(
            "SELECT COUNT(*), COALESCE(MAX(epoch_ms), 0) FROM pending WHERE session_id = ?", session.id
        )
        return SessionHeartbeat(
            sealedPackets = session.packetCount,
            pendingPackets = pending,
            chunks = chunks,
            lastEventEpochMs = maxOf(lastChunkMs, lastPendingMs).takeIf { it > 0 },
            ended = session.endedEpochMs != null,
        )
    }

    private fun countAndLatest(sql: String, sessionId: Long): Pair<Long, Long> =
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1) to rows.getLong(2)
            }
        }

    /** Prot names present in one chunk, so export can skip decoding a chunk with nothing to redact. */
    fun protNamesIn(chunkOrdinal: Long, sessionId: Long): Set<String> =
        connection.prepareStatement(
            """
            SELECT p.name FROM chunk c
            JOIN chunk_prot cp ON cp.chunk_id = c.id
            JOIN prot p ON p.id = cp.prot_id
            WHERE c.session_id = ? AND c.ordinal = ?
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, sessionId)
            statement.setLong(2, chunkOrdinal)
            statement.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }

    /** `(direction, opcode)` for every prot named in [names], for this session's revision. */
    fun opcodesFor(revision: Int, names: Set<String>): Set<Pair<Int, Int>> {
        if (names.isEmpty()) return emptySet()
        val placeholders = names.joinToString(",") { "?" }
        return connection.prepareStatement(
            "SELECT dir, opcode FROM prot WHERE revision = ? AND name IN ($placeholders)"
        ).use { statement ->
            statement.setInt(1, revision)
            for ((i, name) in names.withIndex()) statement.setString(i + 2, name)
            statement.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(rows.getInt(1) to rows.getInt(2)) }
            }
        }
    }

    override fun close() {
        runCatching { connection.close() }
    }

    companion object {

        /**
         * The stored uuid is 16 raw bytes; rendering it the canonical dashed way means the session
         * file on disk, the id on the wire and the id on the server all read identically, which is
         * what makes correlating them possible at a glance.
         */
        private fun canonicalUuid(hex: String): String {
            val lower = hex.lowercase()
            if (lower.length != 32) return lower
            return buildString {
                append(lower, 0, 8); append('-')
                append(lower, 8, 12); append('-')
                append(lower, 12, 16); append('-')
                append(lower, 16, 20); append('-')
                append(lower, 20, 32)
            }
        }

        /**
         * Rewrites a frame with the redacted prots' bodies removed. The events stay, in order, with
         * empty bodies - the same treatment the capture policy gives noise prots, so a reader sees a
         * withheld body rather than a hole and can tell the two apart from the manifest.
         */
        fun redact(chunk: Chunk, redactedOpcodes: Set<Pair<Int, Int>>): ByteArray {
            val events = ChunkFrame.open(chunk.frame, chunk.firstSeq)
            var changed = false
            val rewritten = events.map { event ->
                if ((event.dir to event.opcode) !in redactedOpcodes || event.body.isEmpty()) {
                    event
                } else {
                    changed = true
                    ChunkEvent(
                        seq = event.seq,
                        epochMs = event.epochMs,
                        monoNs = event.monoNs,
                        gameTick = event.gameTick,
                        dir = event.dir,
                        opcode = event.opcode,
                        quality = PacketSchema.Quality.OK,
                        body = ByteArray(0),
                    )
                }
            }
            return if (changed) ChunkFrame.seal(rewritten).frame else chunk.frame
        }
    }
}
