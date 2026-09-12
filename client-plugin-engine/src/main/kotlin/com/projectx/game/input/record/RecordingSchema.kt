package com.projectx.game.input.record

import java.sql.Connection

/**
 * Layout of a player's recording database.
 *
 * Sessions are rows, not appended file headers - the previous format wrote a fresh header into the middle of
 * one growing file and the reader only ever looked at offset zero, so every session after the first was
 * parsed as garbage.
 *
 * Two things deliberately absent because they are derivable rather than observed: modifier state (the
 * LSHIFT/LCTRL/LALT key events are themselves recorded, so a reader reconstructs exact per-event modifiers
 * with no sampling cost and no staleness) and drag state (the button down/up pairs give it).
 *
 * ⛔ Camera state is absent for a different reason and must stay absent: the camera is out of scope entirely,
 * never recorded and never driven.
 */
object RecordingSchema {
    const val VERSION = 3

    /**
     * The lookup table is re-seeded on every open, so a new kind needs no migration.
     *
     * [wireName] is the spelling the model contract uses for the same concept; storing that rather than the
     * enum name keeps one vocabulary across the contract, the database and the reader.
     */
    enum class Kind(val code: Int, val wireName: String) {
        MOUSE_MOTION(0, "mouse_motion"),
        MOUSE_BUTTON(1, "mouse_button"),
        MOUSE_SCROLL(2, "mouse_scroll"),
        KEYBOARD(3, "keyboard"),

        /** Typed text. Carries its character in `key_code`; it has no press state and no position. */
        KEY_CHAR(4, "key_char"),
    }

    /**
     * Which capture path produced a row. The old format interleaved the first two under one opcode, so a
     * ~1 ms local stream and a ~1-per-tick server-bound stream were indistinguishable to the reader.
     *
     * Two columns change meaning with the source, and a reader has to respect it:
     *  - `button` holds a [com.projectx.game.input.MouseButton] id for [RAW_HOOK] and [POLLED], but the
     *    client's own raw ring flag for [SERVER_RING]. That flag's encoding is not reverse engineered, so it
     *    is stored verbatim rather than guessed into a named button - the previous format recorded every
     *    non-zero flag as a left press with no matching release.
     *  - `client_timestamp_ms` is only present on [SERVER_RING] rows, where it is the timestamp the client
     *    puts in the packet. Every other row is timed by `mono_ns` alone.
     */
    enum class Source(val code: Int) {
        /** The client's own input dispatch, at OS event rate. */
        RAW_HOOK(0),

        /** Drained from the server-bound click ring, one entry per tick. */
        SERVER_RING(1),

        /** Sampled from a shared state byte once per tick, so its timing is tick-granular at best. */
        POLLED(2),

        /**
         * Never written by the engine. Reserved for rows imported from the pre-database format, which
         * interleaved the hook stream and the server-bound ring under one opcode with no way to separate them
         * afterwards. Present so those rows still join to a named source instead of a dangling code.
         */
        UNKNOWN(3),
    }

    /**
     * Every statement, in order. Exposed as data so `ai-input/spec/recording_schema_v3.sql` can mirror it and
     * `RecordingSchemaMirrorTest` can fail the build when the two drift - the Python importer creates the same
     * tables, and a silent divergence there produces a database the reader misinterprets rather than rejects.
     */
    val statements: List<String> = listOf(
        """
            CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER NOT NULL
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS event_kind (
                code INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS event_source (
                code INTEGER PRIMARY KEY,
                name TEXT NOT NULL UNIQUE
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS session (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                player            TEXT    NOT NULL,
                started_epoch_ms  INTEGER NOT NULL,
                ended_epoch_ms    INTEGER,
                mono_base_ns      INTEGER NOT NULL,
                platform          TEXT    NOT NULL,
                arch              TEXT    NOT NULL,
                client_build      TEXT    NOT NULL,
                keycode_namespace TEXT    NOT NULL,
                viewport_w        INTEGER NOT NULL,
                viewport_h        INTEGER NOT NULL,
                dropped_events    INTEGER NOT NULL DEFAULT 0,
                provenance        TEXT    NOT NULL
            )
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS event (
                session_id   INTEGER NOT NULL REFERENCES session(id),
                seq          INTEGER NOT NULL,
                mono_ns      INTEGER NOT NULL,
                kind         INTEGER NOT NULL REFERENCES event_kind(code),
                source       INTEGER NOT NULL REFERENCES event_source(code),
                game_tick    INTEGER NOT NULL,
                x            INTEGER,
                y            INTEGER,
                button       INTEGER,
                pressed      INTEGER,
                scroll_delta INTEGER,
                key_code     INTEGER,
                client_timestamp_ms INTEGER,
                PRIMARY KEY (session_id, seq)
            ) WITHOUT ROWID
        """.trimIndent(),
        """
            CREATE TABLE IF NOT EXISTS tick_context (
                session_id      INTEGER NOT NULL REFERENCES session(id),
                mono_ns         INTEGER NOT NULL,
                game_tick       INTEGER NOT NULL,
                main_state      INTEGER NOT NULL,
                player_x        INTEGER NOT NULL,
                player_y        INTEGER NOT NULL,
                plane           INTEGER NOT NULL,
                overlay_capture INTEGER NOT NULL,
                PRIMARY KEY (session_id, mono_ns)
            ) WITHOUT ROWID
        """.trimIndent(),
        """
            CREATE INDEX IF NOT EXISTS event_by_time ON event(session_id, mono_ns)
        """.trimIndent(),
    )

    fun create(connection: Connection) {
        connection.createStatement().use { statement ->
            for (sql in statements) statement.executeUpdate(sql)
        }
        seedLookup(connection, "event_kind", Kind.entries.map { it.code to it.wireName })
        seedLookup(connection, "event_source", Source.entries.map { it.code to it.name })
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
                "recording database is schema v$existing but this engine writes v$VERSION - " +
                    "refusing to append rows a newer reader would misinterpret"
            )
            existing < VERSION -> throw IllegalStateException(
                "recording database is schema v$existing and no migration to v$VERSION exists yet"
            )
        }
    }
}
