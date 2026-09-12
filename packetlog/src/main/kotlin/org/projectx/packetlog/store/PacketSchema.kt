package org.projectx.packetlog.store

import java.sql.Connection

/**
 * Layout of a packet-log database. The same DDL backs the capture database, the archive and the
 * quarantine store, so a chunk is byte-identical wherever it lands and a misrouted session can be
 * moved rather than re-encoded.
 *
 * Packets are not rows. One row per packet costs more than the packets themselves once an index is
 * added, so they live in [chunk] as compressed prot-grouped frames and [chunkProt] carries the
 * coarse index an analyst actually filters on.
 *
 * Bodies are keyed by `(revision, dir, opcode)` and never by name: prot names are still being
 * rebuilt, so a name is a label that improves over time rather than an identity. `prot_table_hash`
 * records which labelling was in force at capture, so re-reading old sessions through a renamed
 * table cannot silently relabel history.
 */
object PacketSchema {
    const val VERSION = 1

    enum class Direction(val code: Int, val wireName: String) {
        SERVER_TO_CLIENT(0, "s2c"),
        CLIENT_TO_SERVER(1, "c2s"),

        /** Login/RSA handshake bytes, captured before prots and ISAAC carry meaning. */
        RAW(2, "raw"),
    }

    enum class Codec(val code: Int, val wireName: String) {
        STORE(0, "store"),
        DEFLATE_RAW(1, "deflate_raw"),
        LZMA1_RAW(2, "lzma1_raw"),
    }

    /**
     * Why a body is or is not trustworthy. Anything other than [OK] still consumes a `seq` so the
     * sequence stays dense and every hole is explained by a row rather than by absence.
     */
    enum class Quality(val code: Int, val wireName: String) {
        OK(0, "ok"),

        /** No in-flight (opcode, size) matched the hook, so the body was withheld, never fabricated. */
        DESYNC_WITHHELD(1, "desync_withheld"),
        OVERSIZE_WITHHELD(2, "oversize_withheld"),
        DROPPED_OVERFLOW(3, "dropped_overflow"),
        TRUNCATED(4, "truncated"),
    }

    /** Only ever advances. A sealed chunk's payload columns are never rewritten. */
    enum class ChunkState(val code: Int, val wireName: String) {
        SEALED(0, "sealed"),
        VERIFIED(1, "verified"),
        PROMOTED(2, "promoted"),
        UPLOADED(3, "uploaded"),
    }

    /** How much of a prot to keep. Telemetry and our own mouse history are a quarter of all bytes. */
    enum class CaptureMode(val code: Int, val wireName: String) {
        FULL(0, "full"),
        BODY_ONLY(1, "body_only"),
        COUNT_ONLY(2, "count_only"),
        DROP(3, "drop"),
    }

    /**
     * Which game a session belongs to. Determined from the launcher's exported profile cross-checked
     * against the connected peer; a disagreement or an unknown peer is [UNKNOWN], which is filed to
     * quarantine and never uploaded rather than being guessed into one of the other two.
     */
    enum class ServerProfile(val code: Int, val wireName: String) {
        LIVE(0, "live"),
        LOCAL(1, "local"),
        UNKNOWN(2, "unknown"),
    }

    val statements: List<String> = listOf(
        """
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER NOT NULL
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS direction (
                code INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS codec (
                code INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS quality (
                code INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS chunk_state (
                code INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS capture_mode (
                code INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS server_profile (
                code INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS session (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid             BLOB    NOT NULL UNIQUE,
                player           TEXT,
                world            INTEGER,
                started_epoch_ms INTEGER NOT NULL,
                ended_epoch_ms   INTEGER,
                mono_base_ns     INTEGER NOT NULL,
                revision         INTEGER NOT NULL,
                prot_table_hash  BLOB    NOT NULL,
                platform         TEXT    NOT NULL,
                arch             TEXT    NOT NULL,
                client_build     TEXT    NOT NULL,
                engine_build     TEXT    NOT NULL,
                server_profile   INTEGER NOT NULL REFERENCES server_profile(code),
                peer_hash        BLOB,
                packet_count     INTEGER NOT NULL DEFAULT 0,
                body_bytes       INTEGER NOT NULL DEFAULT 0,
                dropped_count    INTEGER NOT NULL DEFAULT 0,
                quarantine_count INTEGER NOT NULL DEFAULT 0,
                consent_version  INTEGER NOT NULL DEFAULT 0,
                upload_opt_in    INTEGER NOT NULL DEFAULT 0,
                provenance       TEXT    NOT NULL,
                close_reason     TEXT
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS prot (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                revision  INTEGER NOT NULL,
                dir       INTEGER NOT NULL REFERENCES direction(code),
                opcode    INTEGER NOT NULL,
                name      TEXT    NOT NULL,
                wire_size INTEGER NOT NULL,
                UNIQUE (revision, dir, opcode)
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS chunk (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id    INTEGER NOT NULL REFERENCES session(id),
                ordinal       INTEGER NOT NULL,
                state         INTEGER NOT NULL REFERENCES chunk_state(code),
                first_seq     INTEGER NOT NULL,
                count         INTEGER NOT NULL,
                first_ms      INTEGER NOT NULL,
                last_ms       INTEGER NOT NULL,
                first_mono_ns INTEGER NOT NULL,
                first_tick    INTEGER NOT NULL,
                last_tick     INTEGER NOT NULL,
                body_bytes    INTEGER NOT NULL,
                plain_bytes   INTEGER NOT NULL,
                stored_bytes  INTEGER NOT NULL,
                codec         INTEGER NOT NULL REFERENCES codec(code),
                plain_sha256  BLOB    NOT NULL,
                sealed_ms     INTEGER NOT NULL,
                frame         BLOB    NOT NULL,
                UNIQUE (session_id, ordinal)
            )
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS chunk_by_time ON chunk(session_id, first_ms)
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS chunk_by_tick ON chunk(session_id, first_tick, last_tick)
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS chunk_prot (
                chunk_id     INTEGER NOT NULL REFERENCES chunk(id),
                prot_id      INTEGER NOT NULL REFERENCES prot(id),
                local_index  INTEGER NOT NULL,
                packet_count INTEGER NOT NULL,
                body_bytes   INTEGER NOT NULL,
                first_tick   INTEGER NOT NULL,
                last_tick    INTEGER NOT NULL,
                PRIMARY KEY (chunk_id, prot_id)
            ) WITHOUT ROWID
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS chunk_prot_by_prot ON chunk_prot(prot_id, chunk_id)
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS tick (
                session_id   INTEGER NOT NULL REFERENCES session(id),
                tick         INTEGER NOT NULL,
                first_ms     INTEGER NOT NULL,
                last_ms      INTEGER NOT NULL,
                packet_count INTEGER NOT NULL,
                PRIMARY KEY (session_id, tick)
            ) WITHOUT ROWID
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS capture_policy (
                revision    INTEGER NOT NULL,
                opcode_name TEXT    NOT NULL,
                mode        INTEGER NOT NULL REFERENCES capture_mode(code),
                PRIMARY KEY (revision, opcode_name)
            ) WITHOUT ROWID
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS pending (
                session_id INTEGER NOT NULL REFERENCES session(id),
                seq        INTEGER NOT NULL,
                epoch_ms   INTEGER NOT NULL,
                mono_ns    INTEGER NOT NULL,
                game_tick  INTEGER NOT NULL,
                dir        INTEGER NOT NULL REFERENCES direction(code),
                opcode     INTEGER NOT NULL,
                quality    INTEGER NOT NULL REFERENCES quality(code),
                body       BLOB    NOT NULL,
                PRIMARY KEY (session_id, seq)
            ) WITHOUT ROWID
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS upload (
                chunk_id        INTEGER PRIMARY KEY REFERENCES chunk(id),
                attempts        INTEGER NOT NULL DEFAULT 0,
                next_attempt_ms INTEGER,
                last_error      TEXT,
                uploaded_ms     INTEGER,
                remote_etag     TEXT
            )
        """.trimIndent(),
    )

    fun create(connection: Connection) {
        connection.createStatement().use { statement ->
            for (sql in statements) statement.executeUpdate(sql)
        }
        seedLookup(connection, "direction", Direction.entries.map { it.code to it.wireName })
        seedLookup(connection, "codec", Codec.entries.map { it.code to it.wireName })
        seedLookup(connection, "quality", Quality.entries.map { it.code to it.wireName })
        seedLookup(connection, "chunk_state", ChunkState.entries.map { it.code to it.wireName })
        seedLookup(connection, "capture_mode", CaptureMode.entries.map { it.code to it.wireName })
        seedLookup(connection, "server_profile", ServerProfile.entries.map { it.code to it.wireName })
        stampVersion(connection)
    }

    private fun seedLookup(connection: Connection, table: String, rows: List<Pair<Int, String>>) {
        connection.prepareStatement("INSERT OR REPLACE INTO $table(code, name) VALUES (?, ?)").use { statement ->
            for ((code, name) in rows) {
                statement.setInt(1, code)
                statement.setString(2, name)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun stampVersion(connection: Connection) {
        val existing = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM schema_version LIMIT 1").use { rows ->
                if (rows.next()) rows.getInt(1) else null
            }
        }
        when {
            existing == null ->
                connection.prepareStatement("INSERT INTO schema_version(version) VALUES (?)").use {
                    it.setInt(1, VERSION)
                    it.executeUpdate()
                }
            existing > VERSION -> throw IllegalStateException(
                "packet database is schema v$existing but this build writes v$VERSION - " +
                    "refusing to append rows an older reader would misinterpret"
            )
            existing < VERSION -> throw IllegalStateException(
                "packet database is schema v$existing and no migration to v$VERSION exists yet"
            )
        }
    }
}
