package org.projectx.packetlog

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import org.projectx.core.sqlite.SqliteDriver
import org.projectx.packetlog.chunk.ChunkFrame
import org.projectx.packetlog.store.PacketSchema
import org.projectx.packetlog.tool.TextLogImport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapturePolicyTest {

    /** A dump exercising one telemetry prot, one input prot, and one prot that must be untouched. */
    private fun sampleLog(): File {
        val file = File(Files.createTempDirectory("packetlog-policy").toFile(), "sample.log")
        file.writeText(
            """
            # Packet log started at 09:00:00.000
            [09:00:00.100] S> TELEMETRY_GRID_VALUES_DELTA (op=130, 4B)
                hex: 01 02 03 04
            [09:00:00.200] C> EVENT_MOUSE_MOVE (op=33, 5B)
                hex: 0a 0b 0c 0d 0e
            [09:00:00.300] C> EVENT_KEYBOARD (op=25, 3B)
                hex: 11 12 13
            [09:00:00.400] S> VARP_LARGE (op=4, 6B)
                hex: e3 1c 00 00 00 10
            [09:00:00.500] C> EVENT_MOUSE_CLICK (op=65, 6B)
                hex: 20 21 22 23 24 25

            """.trimIndent()
        )
        return file
    }

    private fun bodiesByOpcode(db: File): Map<Pair<Int, Int>, ByteArray> {
        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${db.absolutePath}?mode=ro").use { connection ->
            connection.prepareStatement("SELECT first_seq, frame FROM chunk ORDER BY ordinal").use { statement ->
                statement.executeQuery().use { rows ->
                    val out = HashMap<Pair<Int, Int>, ByteArray>()
                    while (rows.next()) {
                        for (event in ChunkFrame.open(rows.getBytes(2), rows.getLong(1))) {
                            out[event.dir to event.opcode] = event.body
                        }
                    }
                    return out
                }
            }
        }
    }

    private fun import(keepInput: Boolean): Map<Pair<Int, Int>, ByteArray> {
        val db = File(Files.createTempDirectory("packetlog-policy-db").toFile(), "archive.db")
        val report = TextLogImport.import(sampleLog(), db, PacketSchema.ServerProfile.LOCAL, keepInput)
        assertEquals(5, report.packets, "every event is recorded whatever the policy says")
        return bodiesByOpcode(db)
    }

    private val s2c = PacketSchema.Direction.SERVER_TO_CLIENT.code
    private val c2s = PacketSchema.Direction.CLIENT_TO_SERVER.code

    @Test
    fun `telemetry bodies are dropped and game content is untouched`() {
        for (keepInput in listOf(false, true)) {
            val bodies = import(keepInput)
            assertEquals(0, bodies.getValue(s2c to 130).size, "telemetry body dropped (keepInput=$keepInput)")
            assertEquals(6, bodies.getValue(s2c to 4).size, "a content prot is never touched")
        }
    }

    /** The wire copy of input exists for the training pipeline; without it there is no consumer. */
    @Test
    fun `input event bodies are kept only when training capture wants them`() {
        val without = import(keepInput = false)
        for (opcode in listOf(33, 25, 65)) {
            assertEquals(0, without.getValue(c2s to opcode).size, "input body dropped for opcode $opcode")
        }

        val with = import(keepInput = true)
        assertEquals(5, with.getValue(c2s to 33).size, "mouse move body kept")
        assertEquals(3, with.getValue(c2s to 25).size, "keyboard body kept")
        assertEquals(6, with.getValue(c2s to 65).size, "mouse click body kept")
    }

    /** Emptying a body must not remove the event: the sequence has to stay dense. */
    @Test
    fun `dropping bodies never drops events`() {
        val db = File(Files.createTempDirectory("packetlog-density").toFile(), "archive.db")
        TextLogImport.import(sampleLog(), db, PacketSchema.ServerProfile.LOCAL)
        SqliteDriver.register()
        DriverManager.getConnection("jdbc:sqlite:file:${db.absolutePath}?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT first_seq, count FROM chunk ORDER BY ordinal").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0L, rows.getLong(1), "the sequence starts at zero")
                    assertEquals(5, rows.getInt(2), "and covers every event with no holes")
                }
            }
        }
    }
}
