package org.projectx.packetlog.store

import java.sql.Connection
import org.projectx.packetlog.store.PacketSchema.CaptureMode

/**
 * How much of each prot to keep, as recorded in the database.
 *
 * This layer only knows about prots whose bodies are *never* worth keeping. Prots that are worth
 * keeping only under some runtime condition are the caller's business - see the input-event group in
 * the engine's recorder, which is kept only while input training capture is running.
 *
 * Nothing defaults to [CaptureMode.DROP]. Dropping an event removes its sequence number entirely,
 * which is the one thing the store's density invariant does not allow to happen silently, so it is
 * available but never on by default.
 */
class CapturePolicy(private val modes: Map<Pair<Int, Int>, CaptureMode>) {

    fun modeFor(dir: Int, opcode: Int): CaptureMode =
        modes[dir to opcode] ?: CaptureMode.FULL

    companion object {

        /**
         * Jagex's own instrumentation. It says nothing about game content and is a tenth of all
         * captured bytes, so its body is dropped unconditionally while the events stay in sequence.
         */
        private val DEFAULT_COUNT_ONLY = listOf("TELEMETRY_GRID_VALUES_DELTA", "TELEMETRY_GRID_FULL")

        /**
         * The client's own outbound input. Worth keeping only while input training capture is
         * running - that is the sole consumer, and it records the same input directly at OS event
         * rate with far more detail than the wire carries. Every other caller keeps the events in
         * sequence with empty bodies, so their timing survives without the volume: mouse movement
         * alone is the largest prot by bytes in a normal session.
         *
         * Named here rather than in each caller so the engine and the offline importer cannot drift.
         */
        val INPUT_EVENT_PROTS = setOf("EVENT_MOUSE_MOVE", "EVENT_MOUSE_CLICK", "EVENT_KEYBOARD")

        /** Resolves [names] to this revision's client opcodes; prots it does not have are skipped. */
        fun clientOpcodesFor(connection: Connection, revision: Int, names: Set<String>): IntArray {
            val placeholders = names.joinToString(",") { "?" }
            return connection.prepareStatement(
                "SELECT opcode FROM prot WHERE revision = ? AND dir = ? AND name IN ($placeholders)"
            ).use { statement ->
                statement.setInt(1, revision)
                statement.setInt(2, PacketSchema.Direction.CLIENT_TO_SERVER.code)
                for ((i, name) in names.withIndex()) statement.setString(i + 3, name)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getInt(1)) }.toIntArray()
                }
            }
        }

        /** Seeds the defaults for prots this revision actually has, then reads the table back. */
        fun install(connection: Connection, revision: Int): CapturePolicy {
            connection.prepareStatement(
                "INSERT OR IGNORE INTO capture_policy(revision, opcode_name, mode) VALUES (?, ?, ?)"
            ).use { statement ->
                for (name in DEFAULT_COUNT_ONLY) {
                    statement.setInt(1, revision)
                    statement.setString(2, name)
                    statement.setInt(3, CaptureMode.COUNT_ONLY.code)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            return load(connection, revision)
        }

        fun load(connection: Connection, revision: Int): CapturePolicy {
            val modes = HashMap<Pair<Int, Int>, CaptureMode>()
            connection.prepareStatement(
                """
                SELECT p.dir, p.opcode, c.mode
                FROM capture_policy c
                JOIN prot p ON p.revision = c.revision AND p.name = c.opcode_name
                WHERE c.revision = ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, revision)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val code = rows.getInt(3)
                        val mode = CaptureMode.entries.firstOrNull { it.code == code } ?: continue
                        if (mode != CaptureMode.FULL) modes[rows.getInt(1) to rows.getInt(2)] = mode
                    }
                }
            }
            return CapturePolicy(modes)
        }
    }
}
