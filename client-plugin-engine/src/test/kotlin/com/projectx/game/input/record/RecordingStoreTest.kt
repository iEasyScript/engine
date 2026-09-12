package com.projectx.game.input.record

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The previous format appended a fresh header into one growing file while the reader only ever parsed the
 * header at offset zero, so every session after the first decoded as garbage. These tests pin the property
 * that replaced it: sessions are rows, and a second session cannot disturb the first.
 */
class RecordingStoreTest {
    private val directory: File = Files.createTempDirectory("projectx-recording-test").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun open(player: String = "TestPlayer", viewportWidth: Int = 2560, viewportHeight: Int = 1440) =
        RecordingStore.open(
            player = player,
            startedEpochMs = 1_700_000_000_000,
            monoBaseNanos = 5_000_000_000,
            platform = "linux",
            arch = "x86_64",
            clientBuild = "949-4",
            keycodeNamespace = "sdl",
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            provenance = "test",
            directory = directory,
        )

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T {
        val path = File(directory, "input.db").absolutePath
        DriverManager.getConnection("jdbc:sqlite:file:$path?mode=ro").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> return read(rows) }
            }
        }
    }

    @Test
    fun `four start-stop cycles produce four independent sessions`() {
        val ids = mutableListOf<Long>()
        repeat(4) { cycle ->
            open().use { store ->
                ids.add(store.sessionId)
                store.beginFlush()
                repeat(cycle + 1) { i ->
                    store.addEvent(
                        monoNanos = 1_000L * (i + 1),
                        kind = RecordingSchema.Kind.MOUSE_MOTION,
                        source = RecordingSchema.Source.RAW_HOOK,
                        gameTick = 100 + i,
                        x = 10 * i,
                        y = 20 * i,
                    )
                }
                store.commitFlush()
                store.finishSession(1_700_000_001_000, droppedEvents = 0)
            }
        }

        assertEquals(4, ids.distinct().size, "each session must get its own id")
        assertEquals(4, query("SELECT COUNT(*) FROM session") { it.next(); it.getInt(1) })

        // 1 + 2 + 3 + 4 rows, and each session's own sequence starts at zero.
        assertEquals(10, query("SELECT COUNT(*) FROM event") { it.next(); it.getInt(1) })
        for ((index, id) in ids.withIndex()) {
            val count = query("SELECT COUNT(*) FROM event WHERE session_id = $id") { it.next(); it.getInt(1) }
            assertEquals(index + 1, count, "session $id row count")
            val firstSeq = query("SELECT MIN(seq) FROM event WHERE session_id = $id") { it.next(); it.getLong(1) }
            assertEquals(0L, firstSeq, "session $id must number its own events from zero")
        }
    }

    @Test
    fun `session metadata survives the round trip`() {
        open(viewportWidth = 3440, viewportHeight = 1440).use { store ->
            store.finishSession(1_700_000_009_000, droppedEvents = 7)
        }

        query(
            """
            SELECT player, started_epoch_ms, ended_epoch_ms, mono_base_ns, platform, arch, client_build,
                   keycode_namespace, viewport_w, viewport_h, dropped_events, provenance
            FROM session
            """.trimIndent()
        ) { rows ->
            assertTrue(rows.next())
            assertEquals("TestPlayer", rows.getString("player"))
            assertEquals(1_700_000_000_000, rows.getLong("started_epoch_ms"))
            assertEquals(1_700_000_009_000, rows.getLong("ended_epoch_ms"))
            assertEquals(5_000_000_000, rows.getLong("mono_base_ns"))
            assertEquals("linux", rows.getString("platform"))
            assertEquals("x86_64", rows.getString("arch"))
            assertEquals("949-4", rows.getString("client_build"))
            assertEquals("sdl", rows.getString("keycode_namespace"))
            // The old format stored no resolution at all, so every recording was normalized as if 1080p.
            assertEquals(3440, rows.getInt("viewport_w"))
            assertEquals(1440, rows.getInt("viewport_h"))
            assertEquals(7, rows.getInt("dropped_events"))
            assertEquals("test", rows.getString("provenance"))
        }
    }

    @Test
    fun `capture paths stay distinguishable and unset columns stay null`() {
        open().use { store ->
            store.beginFlush()
            store.addEvent(
                monoNanos = 1_000, kind = RecordingSchema.Kind.MOUSE_MOTION,
                source = RecordingSchema.Source.RAW_HOOK, gameTick = 1, x = 5, y = 6,
            )
            store.addEvent(
                monoNanos = 2_000, kind = RecordingSchema.Kind.MOUSE_BUTTON,
                source = RecordingSchema.Source.POLLED, gameTick = 1, x = 5, y = 6,
                button = 3, pressed = true,
            )
            store.addEvent(
                monoNanos = 3_000, kind = RecordingSchema.Kind.KEYBOARD,
                source = RecordingSchema.Source.RAW_HOOK, gameTick = 2, keyCode = 0x40000052, pressed = false,
            )
            store.commitFlush()
        }

        query(
            """
            SELECT event_kind.name AS kind, event_source.name AS source, x, y, button, pressed, key_code
            FROM event
            JOIN event_kind   ON event_kind.code   = event.kind
            JOIN event_source ON event_source.code = event.source
            ORDER BY seq
            """.trimIndent()
        ) { rows ->
            assertTrue(rows.next())
            assertEquals("mouse_motion", rows.getString("kind"))
            assertEquals("RAW_HOOK", rows.getString("source"))
            rows.getInt("button")
            assertTrue(rows.wasNull(), "a motion row carries no button")

            assertTrue(rows.next())
            assertEquals("mouse_button", rows.getString("kind"))
            // A tick-polled button cannot be passed off as hook-rate input; the old format had no way to say so.
            assertEquals("POLLED", rows.getString("source"))
            assertEquals(3, rows.getInt("button"))
            assertEquals(1, rows.getInt("pressed"))

            assertTrue(rows.next())
            assertEquals("keyboard", rows.getString("kind"))
            assertEquals(0x40000052, rows.getInt("key_code"))
            assertEquals(0, rows.getInt("pressed"))
            rows.getInt("x")
            assertTrue(rows.wasNull(), "a keyboard row carries no position")
        }
    }

    @Test
    fun `a server-ring row keeps its raw flag and the client's own timestamp`() {
        open().use { store ->
            store.beginFlush()
            store.addEvent(
                monoNanos = 4_000, kind = RecordingSchema.Kind.MOUSE_BUTTON,
                source = RecordingSchema.Source.SERVER_RING, gameTick = 9, x = 640, y = 480,
                button = 5, pressed = null, clientTimestampMs = 1_700_000_002_345,
            )
            store.commitFlush()
        }

        query("SELECT button, pressed, client_timestamp_ms FROM event") { rows ->
            assertTrue(rows.next())
            // Stored verbatim: the flag's encoding is unknown, so it must not be coerced into a button id, and
            // it must not claim a press state the ring never reported.
            assertEquals(5, rows.getInt("button"))
            rows.getInt("pressed")
            assertTrue(rows.wasNull(), "a ring row asserts no press state")
            assertEquals(1_700_000_002_345, rows.getLong("client_timestamp_ms"))
        }
    }

    @Test
    fun `only server-ring rows carry a client timestamp`() {
        open().use { store ->
            store.beginFlush()
            store.addEvent(
                monoNanos = 1_000, kind = RecordingSchema.Kind.MOUSE_MOTION,
                source = RecordingSchema.Source.RAW_HOOK, gameTick = 1, x = 1, y = 2,
            )
            store.commitFlush()
        }
        query("SELECT client_timestamp_ms FROM event") { rows ->
            assertTrue(rows.next())
            rows.getLong(1)
            assertTrue(rows.wasNull(), "a hook row is timed by mono_ns alone")
        }
    }

    @Test
    fun `tick context is joinable to events by time`() {
        open().use { store ->
            store.beginFlush()
            store.addTickContext(
                monoNanos = 10_000, gameTick = 42, mainState = 30,
                playerX = 3222, playerY = 3218, plane = 1, overlayCapture = false,
            )
            store.addEvent(
                monoNanos = 10_500, kind = RecordingSchema.Kind.MOUSE_MOTION,
                source = RecordingSchema.Source.RAW_HOOK, gameTick = 42, x = 100, y = 200,
            )
            store.addTickContext(
                monoNanos = 20_000, gameTick = 43, mainState = 30,
                playerX = 3223, playerY = 3218, plane = 1, overlayCapture = true,
            )
            store.commitFlush()
        }

        // Joining on time rather than on an ordering assumption is the point: the old format wrote each tick's
        // context before draining the previous tick's events, pairing every event with the wrong tick.
        val plane = query(
            """
            SELECT tick_context.player_x
            FROM event
            JOIN tick_context ON tick_context.session_id = event.session_id
                             AND tick_context.mono_ns = (
                                 SELECT MAX(mono_ns) FROM tick_context
                                 WHERE session_id = event.session_id AND mono_ns <= event.mono_ns
                             )
            """.trimIndent()
        ) { rows ->
            assertTrue(rows.next())
            rows.getInt("player_x")
        }
        assertEquals(3222, plane, "the event must resolve to the context in force when it happened")

        assertEquals(
            1,
            query("SELECT COUNT(*) FROM tick_context WHERE overlay_capture = 1") { it.next(); it.getInt(1) },
            "an overlay-capturing tick must be marked so the reader can cut the stream there"
        )
    }

    /**
     * The lookup tables are the contract between the engine's enums and every reader. A code that changes
     * meaning silently mis-decodes every row of that kind, so the spelling is pinned here rather than left to
     * whatever the enum happens to be called.
     */
    @Test
    fun `kind and source vocabularies use their wire spellings`() {
        open().use { }
        val kinds = query("SELECT code, name FROM event_kind ORDER BY code") { rows ->
            buildList { while (rows.next()) add(rows.getInt(1) to rows.getString(2)) }
        }
        assertEquals(
            listOf(0 to "mouse_motion", 1 to "mouse_button", 2 to "mouse_scroll", 3 to "keyboard",
                   4 to "key_char"),
            kinds,
            "event_kind must match the vocabulary the training code and readers expect"
        )
        val sources = query("SELECT code, name FROM event_source ORDER BY code") { rows ->
            buildList { while (rows.next()) add(rows.getInt(1) to rows.getString(2)) }
        }
        assertEquals(
            listOf(0 to "RAW_HOOK", 1 to "SERVER_RING", 2 to "POLLED", 3 to "UNKNOWN"),
            sources,
        )
    }

    @Test
    fun `a typed character records its character and no press state`() {
        open().use { store ->
            store.beginFlush()
            store.addEvent(
                monoNanos = 7_000, kind = RecordingSchema.Kind.KEY_CHAR,
                source = RecordingSchema.Source.RAW_HOOK, gameTick = 3, keyCode = 'q'.code,
            )
            store.commitFlush()
        }
        query("SELECT key_code, pressed, x FROM event") { rows ->
            assertTrue(rows.next())
            assertEquals('q'.code, rows.getInt("key_code"))
            rows.getInt("pressed")
            assertTrue(rows.wasNull(), "a typed character has no press state")
            rows.getInt("x")
            assertTrue(rows.wasNull(), "a typed character has no position")
        }
    }

    @Test
    fun `schema version is stamped once and reused`() {
        open().use { }
        open().use { }
        assertEquals(1, query("SELECT COUNT(*) FROM schema_version") { it.next(); it.getInt(1) })
        assertEquals(
            RecordingSchema.VERSION,
            query("SELECT version FROM schema_version") { it.next(); it.getInt(1) }
        )
    }

    @Test
    fun `an unfinished session is left without an end stamp`() {
        val id = open().use { it.sessionId }
        query("SELECT ended_epoch_ms FROM session WHERE id = $id") { rows ->
            assertTrue(rows.next())
            rows.getLong(1)
            assertTrue(rows.wasNull(), "a session closed without finishSession must read as unfinished")
        }
        assertNotNull(File(directory, "input.db").takeIf { it.isFile })
    }
}
