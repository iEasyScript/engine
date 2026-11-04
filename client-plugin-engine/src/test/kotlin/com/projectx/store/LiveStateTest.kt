package com.projectx.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveStateTest {
    private val now = 10_000_000L

    @Test
    fun `scripts carry their experience, rate and best skill`() {
        val run = LiveRun("AIO Fishing", "AioFishing", "2.1.0", now - 1_800_000L, mapOf("FISHING" to 30_000, "COOKING" to 5_000))
        val json = LiveState.build("RS3 engine abc", GameSnapshot(true, 2_890, 75, 40), listOf(run), now)
        assertEquals(1, json.get("v").asInt)
        assertTrue(json.get("loggedIn").asBoolean)
        assertEquals(2_890, json.get("totalLevel").asInt)
        assertEquals(75, json.get("hp").asInt)
        assertEquals(40, json.get("prayer").asInt)
        assertFalse(json.has("name"))
        val script = json.getAsJsonArray("scripts")[0].asJsonObject
        assertEquals("AioFishing", script.get("internalName").asString)
        assertEquals(now - 1_800_000L, script.get("startedAt").asLong)
        assertEquals(35_000, script.get("xp").asLong)
        assertEquals(70_000, script.get("xpPerHour").asLong)
        assertEquals("FISHING", script.get("skill").asString)
        assertFalse(json.getAsJsonObject("break").get("active").asBoolean)
    }

    @Test
    fun `logged out says nothing about the character`() {
        val json = LiveState.build("v", GameSnapshot.LOGGED_OUT, emptyList(), now)
        assertFalse(json.get("loggedIn").asBoolean)
        assertFalse(json.has("totalLevel") || json.has("hp") || json.has("prayer"))
        assertEquals(0, json.getAsJsonArray("scripts").size())
    }

    @Test
    fun `at most ten scripts are listed`() {
        val runs = (1..12).map { LiveRun("Script $it", "Script$it", null, now, emptyMap()) }
        assertEquals(LiveState.MAX_SCRIPTS, LiveState.build("v", GameSnapshot.LOGGED_OUT, runs, now).getAsJsonArray("scripts").size())
    }

    @Test
    fun `no rate for the first minute`() {
        assertEquals(0, LiveState.perHour(5_000, 59_000))
        assertEquals(300_000, LiveState.perHour(5_000, 60_000))
    }
}
