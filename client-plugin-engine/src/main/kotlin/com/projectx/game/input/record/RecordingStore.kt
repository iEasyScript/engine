package com.projectx.game.input.record

import java.io.File
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types

/**
 * Write side of a player's recording database. One open session, one transaction per tick flush.
 *
 * Not thread-safe by design: every call must come from the game thread, which is where the flush runs.
 */
class RecordingStore private constructor(
    private val connection: Connection,
    val file: File,
    val sessionId: Long,
    val monoBaseNanos: Long,
) : AutoCloseable {

    private val insertEvent: PreparedStatement = connection.prepareStatement(
        """
        INSERT INTO event(session_id, seq, mono_ns, kind, source, game_tick,
                          x, y, button, pressed, scroll_delta, key_code, client_timestamp_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    )

    private val insertContext: PreparedStatement = connection.prepareStatement(
        """
        INSERT OR REPLACE INTO tick_context(session_id, mono_ns, game_tick, main_state,
                                            player_x, player_y, plane, overlay_capture)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    )

    private var sequence: Long = 0

    val eventsWritten: Long get() = sequence

    fun beginFlush() {
        connection.autoCommit = false
    }

    fun commitFlush() {
        insertEvent.executeBatch()
        insertContext.executeBatch()
        connection.commit()
        connection.autoCommit = true
    }

    fun rollbackFlush() {
        runCatching { insertEvent.clearBatch() }
        runCatching { insertContext.clearBatch() }
        runCatching { connection.rollback() }
        runCatching { connection.autoCommit = true }
    }

    fun addTickContext(
        monoNanos: Long,
        gameTick: Int,
        mainState: Int,
        playerX: Int,
        playerY: Int,
        plane: Int,
        overlayCapture: Boolean,
    ) {
        insertContext.setLong(1, sessionId)
        insertContext.setLong(2, monoNanos)
        insertContext.setInt(3, gameTick)
        insertContext.setInt(4, mainState)
        insertContext.setInt(5, playerX)
        insertContext.setInt(6, playerY)
        insertContext.setInt(7, plane)
        insertContext.setInt(8, if (overlayCapture) 1 else 0)
        insertContext.addBatch()
    }

    fun addEvent(
        monoNanos: Long,
        kind: RecordingSchema.Kind,
        source: RecordingSchema.Source,
        gameTick: Int,
        x: Int? = null,
        y: Int? = null,
        button: Int? = null,
        pressed: Boolean? = null,
        scrollDelta: Int? = null,
        keyCode: Int? = null,
        clientTimestampMs: Long? = null,
    ) {
        insertEvent.setLong(1, sessionId)
        insertEvent.setLong(2, sequence++)
        insertEvent.setLong(3, monoNanos)
        insertEvent.setInt(4, kind.code)
        insertEvent.setInt(5, source.code)
        insertEvent.setInt(6, gameTick)
        insertEvent.setNullableInt(7, x)
        insertEvent.setNullableInt(8, y)
        insertEvent.setNullableInt(9, button)
        insertEvent.setNullableInt(10, pressed?.let { if (it) 1 else 0 })
        insertEvent.setNullableInt(11, scrollDelta)
        insertEvent.setNullableInt(12, keyCode)
        if (clientTimestampMs == null) insertEvent.setNull(13, Types.INTEGER)
        else insertEvent.setLong(13, clientTimestampMs)
        insertEvent.addBatch()
    }

    fun finishSession(endedEpochMs: Long, droppedEvents: Long) {
        connection.prepareStatement(
            "UPDATE session SET ended_epoch_ms = ?, dropped_events = ? WHERE id = ?"
        ).use { statement ->
            statement.setLong(1, endedEpochMs)
            statement.setLong(2, droppedEvents)
            statement.setLong(3, sessionId)
            statement.executeUpdate()
        }
    }

    override fun close() {
        runCatching { insertEvent.close() }
        runCatching { insertContext.close() }
        runCatching { connection.close() }
    }

    companion object {
        private const val DATABASE_NAME = "input.db"

        fun defaultDirectory(player: String): File =
            File(File(System.getProperty("user.home"), ".projectx/training"), player)

        fun open(
            player: String,
            startedEpochMs: Long,
            monoBaseNanos: Long,
            platform: String,
            arch: String,
            clientBuild: String,
            keycodeNamespace: String,
            viewportWidth: Int,
            viewportHeight: Int,
            provenance: String,
            directory: File = defaultDirectory(player),
        ): RecordingStore {
            directory.mkdirs()
            val file = File(directory, DATABASE_NAME)

            val connection = connect(file)
            connection.createStatement().use { statement ->
                statement.executeUpdate("PRAGMA journal_mode=WAL")
                statement.executeUpdate("PRAGMA synchronous=NORMAL")
                statement.executeUpdate("PRAGMA foreign_keys=ON")
            }
            RecordingSchema.create(connection)

            val sessionId = insertSession(
                connection, player, startedEpochMs, monoBaseNanos, platform, arch,
                clientBuild, keycodeNamespace, viewportWidth, viewportHeight, provenance,
            )
            return RecordingStore(connection, file, sessionId, monoBaseNanos)
        }

        private fun insertSession(
            connection: Connection,
            player: String,
            startedEpochMs: Long,
            monoBaseNanos: Long,
            platform: String,
            arch: String,
            clientBuild: String,
            keycodeNamespace: String,
            viewportWidth: Int,
            viewportHeight: Int,
            provenance: String,
        ): Long {
            connection.prepareStatement(
                """
                INSERT INTO session(player, started_epoch_ms, mono_base_ns, platform, arch, client_build,
                                    keycode_namespace, viewport_w, viewport_h, provenance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, player)
                statement.setLong(2, startedEpochMs)
                statement.setLong(3, monoBaseNanos)
                statement.setString(4, platform)
                statement.setString(5, arch)
                statement.setString(6, clientBuild)
                statement.setString(7, keycodeNamespace)
                statement.setInt(8, viewportWidth)
                statement.setInt(9, viewportHeight)
                statement.setString(10, provenance)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (!keys.next()) throw IllegalStateException("session insert returned no id")
                    return keys.getLong(1)
                }
            }
        }

        /**
         * `mode=rwc` is spelled out rather than relying on the driver's default. A bare `jdbc:sqlite:<path>`
         * silently creates a database at a mistyped path, and the project bans that form outright because the
         * same mistake against a game cache corrupts it.
         */
        private fun connect(file: File): Connection =
            DriverManager.getConnection("jdbc:sqlite:file:${file.absolutePath}?mode=rwc")

        init {
            // The injected engine loads through a child URLClassLoader that DriverManager's ServiceLoader
            // auto-registration never scans, so a plain getConnection fails with "No suitable driver found".
            runCatching {
                val driver = Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as Driver
                DriverManager.registerDriver(driver)
            }
        }
    }
}

private fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
}
