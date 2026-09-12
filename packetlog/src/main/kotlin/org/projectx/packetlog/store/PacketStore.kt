package org.projectx.packetlog.store

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.UUID
import org.projectx.core.sqlite.SqliteDriver
import org.projectx.packetlog.chunk.ChunkEvent
import org.projectx.packetlog.chunk.SealedChunk

/**
 * Write side of a packet-log database. One open session, one transaction per tick flush.
 *
 * Not thread-safe by design: every call must come from the game thread, which is where the flush
 * runs. Compression happens elsewhere - this class only ever commits an already-sealed frame.
 */
class PacketStore private constructor(
    private val connection: Connection,
    val file: File,
    val sessionId: Long,
    val sessionUuid: UUID,
) : AutoCloseable {

    private val insertPending = connection.prepareStatement(
        """
        INSERT OR REPLACE INTO pending(session_id, seq, epoch_ms, mono_ns, game_tick, dir, opcode, quality, body)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    )

    private val insertTick = connection.prepareStatement(
        """
        INSERT INTO tick(session_id, tick, first_ms, last_ms, packet_count)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(session_id, tick) DO UPDATE SET
            last_ms = max(last_ms, excluded.last_ms),
            packet_count = packet_count + excluded.packet_count
        """.trimIndent()
    )

    private val protIds = HashMap<Long, Long>()

    /** Seeded on open and read back, so an edit to the table takes effect on the next session. */
    val capturePolicy: CapturePolicy = CapturePolicy.install(connection, revisionOf(connection, sessionId))

    var nextSequence: Long = 0
        private set

    var nextOrdinal: Long = 0
        private set

    fun beginFlush() {
        connection.autoCommit = false
    }

    fun commitFlush() {
        insertPending.executeBatch()
        insertTick.executeBatch()
        connection.commit()
        connection.autoCommit = true
    }

    fun rollbackFlush() {
        runCatching { insertPending.clearBatch() }
        runCatching { insertTick.clearBatch() }
        runCatching { connection.rollback() }
        runCatching { connection.autoCommit = true }
    }

    /** Assigns the session-scoped sequence. Every captured event gets one, including withheld ones. */
    fun assignSequence(): Long = nextSequence++

    fun addPending(event: ChunkEvent) {
        insertPending.setLong(1, sessionId)
        insertPending.setLong(2, event.seq)
        insertPending.setLong(3, event.epochMs)
        insertPending.setLong(4, event.monoNs)
        insertPending.setInt(5, event.gameTick)
        insertPending.setInt(6, event.dir)
        insertPending.setInt(7, event.opcode)
        insertPending.setInt(8, event.quality.code)
        insertPending.setBytes(9, event.body)
        insertPending.addBatch()
    }

    fun addTick(tick: Int, firstMs: Long, lastMs: Long, packetCount: Int) {
        insertTick.setLong(1, sessionId)
        insertTick.setInt(2, tick)
        insertTick.setLong(3, firstMs)
        insertTick.setLong(4, lastMs)
        insertTick.setInt(5, packetCount)
        insertTick.addBatch()
    }

    /**
     * Commits a sealed frame and drops the rows it supersedes in the same transaction. Doing the
     * delete separately would let a crash between the two lose a chunk's worth of packets; doing it
     * first would lose them outright.
     */
    fun commitChunk(revision: Int, sealed: SealedChunk) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val chunkId = connection.prepareStatement(
                """
                INSERT INTO chunk(session_id, ordinal, state, first_seq, count, first_ms, last_ms,
                                  first_mono_ns, first_tick, last_tick, body_bytes, plain_bytes,
                                  stored_bytes, codec, plain_sha256, sealed_ms, frame)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, sessionId)
                statement.setLong(2, nextOrdinal)
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
                lastInsertRowId()
            }

            connection.prepareStatement(
                """
                INSERT INTO chunk_prot(chunk_id, prot_id, local_index, packet_count, body_bytes, first_tick, last_tick)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                for (stat in sealed.protStats) {
                    statement.setLong(1, chunkId)
                    statement.setLong(2, protId(revision, stat.dir, stat.opcode))
                    statement.setInt(3, stat.localIndex)
                    statement.setInt(4, stat.packetCount)
                    statement.setLong(5, stat.bodyBytes)
                    statement.setInt(6, stat.firstTick)
                    statement.setInt(7, stat.lastTick)
                    statement.addBatch()
                }
                statement.executeBatch()
            }

            connection.prepareStatement(
                "DELETE FROM pending WHERE session_id = ? AND seq >= ? AND seq <= ?"
            ).use { statement ->
                statement.setLong(1, sessionId)
                statement.setLong(2, sealed.firstSeq)
                statement.setLong(3, sealed.firstSeq + sealed.count - 1)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """
                UPDATE session SET packet_count = packet_count + ?, body_bytes = body_bytes + ?
                WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, sealed.count)
                statement.setLong(2, sealed.bodyBytes)
                statement.setLong(3, sessionId)
                statement.executeUpdate()
            }

            connection.commit()
            nextOrdinal++
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    /** Rows still spilled because the client died before they were sealed. */
    fun readPending(): List<ChunkEvent> =
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

    fun recordDropped(count: Long) {
        connection.prepareStatement("UPDATE session SET dropped_count = dropped_count + ? WHERE id = ?")
            .use {
                it.setLong(1, count)
                it.setLong(2, sessionId)
                it.executeUpdate()
            }
    }

    fun closeSession(endedEpochMs: Long, reason: String) {
        connection.prepareStatement(
            "UPDATE session SET ended_epoch_ms = ?, close_reason = ? WHERE id = ?"
        ).use {
            it.setLong(1, endedEpochMs)
            it.setString(2, reason)
            it.setLong(3, sessionId)
            it.executeUpdate()
        }
    }

    private fun protId(revision: Int, dir: Int, opcode: Int): Long {
        val key = (revision.toLong() shl 40) or (dir.toLong() shl 32) or opcode.toLong()
        return protIds.getOrPut(key) {
            connection.prepareStatement(
                "SELECT id FROM prot WHERE revision = ? AND dir = ? AND opcode = ?"
            ).use { statement ->
                statement.setInt(1, revision)
                statement.setInt(2, dir)
                statement.setInt(3, opcode)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getLong(1)
                    else error("prot ($revision, $dir, $opcode) was never seeded")
                }
            }
        }
    }

    private fun lastInsertRowId(): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }

    /**
     * Reclaims the pages the spill table churned through and folds the write-ahead log back into the
     * database file, so what is on disk after a session is the session rather than the session plus
     * its scratch space.
     */
    fun compact() {
        runCatching {
            connection.createStatement().use { statement ->
                // Checkpoint first: pages the spill table freed are only free once the write-ahead
                // log has been folded in, so vacuuming before this reclaims almost nothing. Each
                // vacuum can itself free more pages, so repeat until it stops making progress.
                var previous = Int.MAX_VALUE
                repeat(8) {
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                    statement.execute("PRAGMA incremental_vacuum")
                    val free = statement.executeQuery("PRAGMA freelist_count").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                    if (free == 0 || free >= previous) return@repeat
                    previous = free
                }
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        }
    }

    override fun close() {
        runCatching { insertPending.close() }
        runCatching { insertTick.close() }
        compact()
        runCatching { connection.close() }
    }

    companion object {

        private fun revisionOf(connection: Connection, sessionId: Long): Int =
            connection.prepareStatement("SELECT revision FROM session WHERE id = ?").use {
                it.setLong(1, sessionId)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }

        fun defaultDirectory(profile: PacketSchema.ServerProfile): File =
            File(File(System.getProperty("user.home"), ".projectx/packetlog"), profile.wireName)

        /**
         * `mode=rwc` is spelled out rather than relying on the driver's default. A bare
         * `jdbc:sqlite:<path>` silently creates a database at a mistyped path, and the project bans
         * that form outright because the same mistake against a game cache corrupts it.
         */
        private fun connect(file: File): Connection {
            SqliteDriver.register()
            return DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=rwc")
        }

        /**
         * [uuid] is supplied rather than minted here so the caller can name the file after the
         * session it holds, which is what keeps two concurrent clients out of each other's way.
         */
        fun open(
            file: File,
            metadata: SessionMetadata,
            protTable: List<ProtEntry>,
            uuid: UUID = UUID.randomUUID(),
        ): PacketStore {
            file.parentFile?.mkdirs()
            val connection = connect(file)
            connection.createStatement().use { statement ->
                // page_size only takes effect before the first table exists; frames spill to overflow
                // pages, and a larger page halves the chain length.
                statement.execute("PRAGMA page_size = 8192")
                // Also before the first table: the spill table churns hundreds of thousands of rows
                // per session, and without this their pages stay on the freelist forever.
                statement.execute("PRAGMA auto_vacuum = INCREMENTAL")
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = NORMAL")
                statement.execute("PRAGMA foreign_keys = ON")
            }
            PacketSchema.create(connection)
            seedProts(connection, protTable)

            val sessionId = insertSession(connection, uuid, metadata)
            return PacketStore(connection, file, sessionId, uuid)
        }

        private fun seedProts(connection: Connection, protTable: List<ProtEntry>) {
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO prot(revision, dir, opcode, name, wire_size) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(revision, dir, opcode) DO UPDATE SET
                        name = excluded.name, wire_size = excluded.wire_size
                    """.trimIndent()
                ).use { statement ->
                    for (entry in protTable) {
                        statement.setInt(1, entry.revision)
                        statement.setInt(2, entry.dir)
                        statement.setInt(3, entry.opcode)
                        statement.setString(4, entry.name)
                        statement.setInt(5, entry.wireSize)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } finally {
                connection.autoCommit = true
            }
        }

        private fun insertSession(connection: Connection, uuid: UUID, metadata: SessionMetadata): Long {
            connection.prepareStatement(
                """
                INSERT INTO session(uuid, player, world, started_epoch_ms, mono_base_ns, revision,
                                    prot_table_hash, platform, arch, client_build, engine_build,
                                    server_profile, peer_hash, consent_version, upload_opt_in, provenance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setBytes(1, uuid.toByteArray())
                statement.setString(2, metadata.player)
                if (metadata.world == null) statement.setNull(3, Types.INTEGER)
                else statement.setInt(3, metadata.world)
                statement.setLong(4, metadata.startedEpochMs)
                statement.setLong(5, metadata.monoBaseNs)
                statement.setInt(6, metadata.revision)
                statement.setBytes(7, metadata.protTableHash)
                statement.setString(8, metadata.platform)
                statement.setString(9, metadata.arch)
                statement.setString(10, metadata.clientBuild)
                statement.setString(11, metadata.engineBuild)
                statement.setInt(12, metadata.serverProfile.code)
                statement.setBytes(13, metadata.peerHash)
                statement.setInt(14, metadata.consentVersion)
                statement.setInt(15, if (metadata.uploadOptIn) 1 else 0)
                statement.setString(16, metadata.provenance)
                statement.executeUpdate()
            }
            return connection.createStatement().use { statement ->
                statement.executeQuery("SELECT last_insert_rowid()").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

        private fun UUID.toByteArray(): ByteArray {
            val bytes = ByteArray(16)
            var high = mostSignificantBits
            var low = leastSignificantBits
            for (i in 7 downTo 0) {
                bytes[i] = (high and 0xFF).toByte()
                high = high ushr 8
                bytes[i + 8] = (low and 0xFF).toByte()
                low = low ushr 8
            }
            return bytes
        }
    }
}

class ProtEntry(val revision: Int, val dir: Int, val opcode: Int, val name: String, val wireSize: Int)

class SessionMetadata(
    val player: String?,
    val world: Int?,
    val startedEpochMs: Long,
    val monoBaseNs: Long,
    val revision: Int,
    val protTableHash: ByteArray,
    val platform: String,
    val arch: String,
    val clientBuild: String,
    val engineBuild: String,
    val serverProfile: PacketSchema.ServerProfile,
    val peerHash: ByteArray?,
    val consentVersion: Int,
    val uploadOptIn: Boolean,
    val provenance: String,
)
