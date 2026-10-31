package com.projectx.store

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptLogExcerptTest {
    private val since = LocalDateTime.of(2026, 10, 30, 0, 0)

    private fun excerpt(vararg lines: String) = ScriptLogExcerpt.excerpt(lines.toList(), "Shattered Worlds", "ShatteredWorlds", since)

    @Test
    fun `keeps every line between the script's markers, clicks included`() {
        val kept = excerpt(
            "[2026-10-30 10:00:00.000] [Gates of Elidinis] Praying",
            "[2026-10-30 10:01:00.000] [Scripts] Started Shattered Worlds v1.0.18 (ShatteredWorlds)",
            "[2026-10-30 10:01:01.000] FIRED SYNTHETIC ActionType.OBJECT_1(1, 2, 3)",
            "[2026-10-30 10:01:02.000] [Shattered Worlds] Entering Shattered Worlds",
            "[2026-10-30 10:02:00.000] [Scripts] Stopped Shattered Worlds (ShatteredWorlds)",
            "[2026-10-30 10:03:00.000] FIRED SYNTHETIC ActionType.WALK(0, 1, 2)",
        )
        assertEquals(4, kept.size)
        assertTrue(kept.none { "Gates" in it || "WALK" in it })
    }

    @Test
    fun `a stack trace stays with the line it belongs to`() {
        val kept = excerpt(
            "[2026-10-30 10:01:00.000] [Scripts] Started Shattered Worlds v1.0.18 (ShatteredWorlds)",
            "[2026-10-30 10:01:01.000] java.lang.IllegalStateException: boom",
            "\tat com.projectx.Foo.bar(Foo.kt:1)",
            "[2026-10-30 10:02:00.000] [Scripts] Stopped Shattered Worlds (ShatteredWorlds)",
            "\tat com.projectx.After.stop(After.kt:1)",
        )
        assertEquals(5, kept.size)
    }

    @Test
    fun `lines older than the window are left out`() {
        val kept = excerpt(
            "[2026-10-29 23:00:00.000] [Scripts] Started Shattered Worlds v1.0.17 (ShatteredWorlds)",
            "[2026-10-29 23:30:00.000] [Shattered Worlds] Eating",
            "[2026-10-30 00:30:00.000] [Shattered Worlds] Fighting",
        )
        assertEquals(listOf("[2026-10-30 00:30:00.000] [Shattered Worlds] Fighting"), kept)
    }

    @Test
    fun `old logs without markers keep the script's lines and what lies close between them`() {
        val kept = excerpt(
            "[2026-10-30 09:00:00.000] [Shattered Worlds] Starting the run",
            "[2026-10-30 09:00:05.000] FIRED SYNTHETIC ActionType.COMPONENT(1, -1, 2)",
            "[2026-10-30 09:00:10.000] [Shattered Worlds] Picking a mutator",
            "[2026-10-30 11:00:00.000] FIRED SYNTHETIC ActionType.WALK(0, 1, 2)",
            "[2026-10-30 12:00:00.000] [Shattered Worlds] Starting the run",
        )
        assertEquals(4, kept.size)
        assertTrue(kept.none { "WALK" in it })
    }

    @Test
    fun `another script's run is not included`() {
        val kept = excerpt(
            "[2026-10-30 10:01:00.000] [Scripts] Started Gates of Elidinis v3.7.2 (GatesOfElidinis)",
            "[2026-10-30 10:01:01.000] [Gates of Elidinis] Praying",
            "[2026-10-30 10:02:00.000] [Scripts] Stopped Gates of Elidinis (GatesOfElidinis)",
        )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `saved secrets and long tokens are hidden`() {
        val masked = ProblemReports.scrub(
            "Entering PIN 4821\nAuthorization: Bearer ${"a".repeat(48)}\nhttps://discord.com/api/webhooks/123/abc",
            listOf("4821"),
        )
        assertTrue("4821" !in masked && "a".repeat(48) !in masked && "webhooks/123" !in masked)
    }
}
