package org.projectx.packetlog.query

import java.sql.Connection

/**
 * A derived, disposable index over one or more archives, built for investigation rather than for
 * storage.
 *
 * The archive answers "keep everything, cheaply". This answers a different question - "what happened
 * around X" - and the two want opposite layouts, so they are separate artifacts rather than one
 * compromise. Everything here can be rebuilt from the archives at any time, which is also what makes
 * adding a decoder after the fact safe: the index is regenerated, the capture is never touched.
 *
 * The design centres on two axes, because every investigation uses both:
 *  - an **anchor**: find events by a field value, in constant time for the ids people start from
 *  - a **window**: everything around that point in tick order, decoded
 */
object QuerySchema {
    const val VERSION = 1

    val statements: List<String> = listOf(
        """
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER NOT NULL
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS session (
                id             INTEGER PRIMARY KEY,
                uuid           TEXT    NOT NULL UNIQUE,
                source         TEXT    NOT NULL,
                kind           TEXT    NOT NULL,
                revision       INTEGER NOT NULL,
                client_build   TEXT    NOT NULL,
                player         TEXT,
                started_ms     INTEGER NOT NULL,
                ended_ms       INTEGER,
                packet_count   INTEGER NOT NULL DEFAULT 0,
                first_tick     INTEGER,
                last_tick      INTEGER,
                decode_ok      INTEGER NOT NULL DEFAULT 0,
                decode_missing INTEGER NOT NULL DEFAULT 0,
                decode_failed  INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS prot (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                revision INTEGER NOT NULL,
                dir      INTEGER NOT NULL,
                opcode   INTEGER NOT NULL,
                name     TEXT    NOT NULL,
                UNIQUE (revision, dir, opcode)
            )
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS prot_by_name ON prot(name, revision)
        """.trimIndent(),
        // One row per captured packet. This is the tick axis, and the reason a window query never
        // has to touch a compressed archive.
        """
            CREATE TABLE IF NOT EXISTS event (
                session_id INTEGER NOT NULL REFERENCES session(id),
                seq        INTEGER NOT NULL,
                tick       INTEGER NOT NULL,
                ms         INTEGER NOT NULL,
                dir        INTEGER NOT NULL,
                prot_id    INTEGER NOT NULL REFERENCES prot(id),
                len        INTEGER NOT NULL,
                status     INTEGER NOT NULL,
                decode_rev INTEGER,
                fields     TEXT,
                PRIMARY KEY (session_id, seq)
            ) WITHOUT ROWID
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS event_by_tick ON event(session_id, tick, seq)
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS event_by_prot ON event(prot_id, session_id, tick)
        """.trimIndent(),
        // What fields exist, per revision. A decoder added later adds rows here; nothing is dropped,
        // so an old row's field still resolves even after the decoder that wrote it has moved on.
        """
            CREATE TABLE IF NOT EXISTS field_def (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                revision   INTEGER NOT NULL,
                prot_name  TEXT    NOT NULL,
                path       TEXT    NOT NULL,
                kind       TEXT    NOT NULL,
                ref_domain TEXT    NOT NULL,
                indexed    INTEGER NOT NULL DEFAULT 0,
                UNIQUE (revision, prot_name, path)
            )
        """.trimIndent(),
        // Only for fields worth constant-time lookup. Everything else stays filterable by scanning
        // one prot's events, which is bounded because prot is itself indexed.
        """
            CREATE TABLE IF NOT EXISTS field_index (
                field_def_id INTEGER NOT NULL REFERENCES field_def(id),
                int_value    INTEGER NOT NULL,
                session_id   INTEGER NOT NULL,
                seq          INTEGER NOT NULL,
                tick         INTEGER NOT NULL,
                PRIMARY KEY (field_def_id, int_value, session_id, seq)
            ) WITHOUT ROWID
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS field_index_by_tick ON field_index(session_id, tick, field_def_id)
        """.trimIndent(),
        """
            CREATE VIRTUAL TABLE IF NOT EXISTS field_text USING fts5(
                text_value,
                prot_name UNINDEXED,
                path UNINDEXED,
                session_id UNINDEXED,
                seq UNINDEXED,
                tick UNINDEXED,
                tokenize = 'unicode61'
            )
        """.trimIndent(),
        // Which decoder produced which rows, so a decoder change can be re-run over exactly the
        // packets it affects instead of over everything.
        """
            CREATE TABLE IF NOT EXISTS decode_rev (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                revision     INTEGER NOT NULL,
                prot_name    TEXT    NOT NULL,
                schema_ver   INTEGER NOT NULL,
                fields_hash  TEXT    NOT NULL,
                first_seen_ms INTEGER NOT NULL,
                UNIQUE (revision, prot_name, schema_ver, fields_hash)
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS session_decode_state (
                session_id INTEGER NOT NULL REFERENCES session(id),
                prot_name  TEXT    NOT NULL,
                decode_rev INTEGER,
                row_count  INTEGER NOT NULL,
                ok_count   INTEGER NOT NULL,
                failed_count INTEGER NOT NULL,
                PRIMARY KEY (session_id, prot_name)
            ) WITHOUT ROWID
        """.trimIndent(),
    )

    fun create(connection: Connection) {
        connection.createStatement().use { statement ->
            for (sql in statements) statement.executeUpdate(sql)
        }
        stampVersion(connection)
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
            existing != VERSION -> throw IllegalStateException(
                "query database is schema v$existing, this build builds v$VERSION - rebuild it"
            )
        }
    }
}
