package com.projectx.store

import org.projectx.core.game.skill.Skill
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunReportTest {
    private val siteSkill = Regex("^[A-Z_]{3,16}$")

    private fun report(
        startedAt: Long = 1_000_000L,
        endedAt: Long = startedAt + 3_600_000L,
        end: RunEnd = RunEnd.SELF_STOP,
        stopMessage: String? = "Out of runes - stopping.",
        xp: Map<String, Int> = mapOf("NECROMANCY" to 52_310, "PRAYER" to 1_200),
        store: Boolean = true,
    ) = RunReport("AioNecromancy", "AIO Necromancy", "1.4.0", store, startedAt, endedAt, end, stopMessage, xp, died = true)

    @Test
    fun `every RS3 skill has a name the site accepts`() {
        Skill.entries.forEach { assertTrue(siteSkill.matches(RunReport.siteSkillName(it)), it.name) }
        assertEquals(29, Skill.entries.map(RunReport::siteSkillName).toSet().size)
    }

    @Test
    fun `defence is spelt the way the site files it`() {
        assertEquals("DEFENCE", RunReport.siteSkillName(Skill.DEFENSE))
        listOf(Skill.NECROMANCY, Skill.ARCHAEOLOGY, Skill.INVENTION, Skill.DIVINATION, Skill.SUMMONING, Skill.DUNGEONEERING, Skill.HUNTER, Skill.CONSTRUCTION, Skill.CONSTITUTION)
            .forEach { assertEquals(it.name, RunReport.siteSkillName(it)) }
    }

    @Test
    fun `experience gained counts only skills that went up`() {
        val start = mapOf(Skill.MINING to 1_000, Skill.DEFENSE to 500, Skill.SMITHING to 2_000, Skill.ATTACK to 10)
        val now = mapOf(Skill.MINING to 53_310, Skill.DEFENSE to 900, Skill.SMITHING to 1_500, Skill.ATTACK to 10)
        assertEquals(mapOf("MINING" to 52_310, "DEFENCE" to 400), RunReport.xpGained(start, now))
    }

    @Test
    fun `no starting point means no experience`() {
        assertEquals(emptyMap(), RunReport.xpGained(emptyMap(), mapOf(Skill.MINING to 5_000)))
    }

    @Test
    fun `duration is whole seconds and never negative`() {
        assertEquals(3_600, report().durationSec)
        assertEquals(90, report(startedAt = 0, endedAt = 89_600).durationSec)
        assertEquals(0, report(startedAt = 5_000, endedAt = 1_000).durationSec)
    }

    @Test
    fun `a fresh mark decides the end, otherwise the script stopped itself`() {
        val now = 100_000L
        assertEquals(RunEnd.MANUAL, RunReport.endFor(RunEnd.MANUAL, now - 2_000, now))
        assertEquals(RunEnd.ACCESS_ENDED, RunReport.endFor(RunEnd.ACCESS_ENDED, now, now))
        assertEquals(RunEnd.SELF_STOP, RunReport.endFor(RunEnd.MANUAL, now - RunReport.MARK_WINDOW_MILLIS, now))
        assertEquals(RunEnd.SELF_STOP, RunReport.endFor(null, 0, now))
    }

    @Test
    fun `end reasons use the site's names`() {
        assertEquals(listOf("MANUAL", "QUEUE", "SELF_STOP", "ACCESS_ENDED", "CLIENT_CLOSED"), RunEnd.entries.map { it.name })
    }

    @Test
    fun `the body carries what the sessions endpoint reads`() {
        val json = report().toJson("RS3 engine abc123")
        assertEquals("store", json.get("source").asString)
        assertEquals("AioNecromancy", json.get("scriptInternalName").asString)
        assertEquals("AIO Necromancy", json.get("scriptName").asString)
        assertEquals("1.4.0", json.get("scriptVersion").asString)
        assertEquals(1_000_000L, json.get("startedAt").asLong)
        assertEquals(4_600_000L, json.get("endedAt").asLong)
        assertEquals(3_600, json.get("durationSec").asInt)
        assertEquals("SELF_STOP", json.get("endReason").asString)
        assertEquals("Out of runes - stopping.", json.get("stopMessage").asString)
        assertEquals(52_310, json.getAsJsonObject("xpGained").get("NECROMANCY").asInt)
        assertEquals(53_510, json.get("xpTotal").asLong)
        assertTrue(json.get("died").asBoolean)
        assertFalse(json.has("kind"))
        assertFalse(json.keySet().any { it.contains("account", ignoreCase = true) || it.contains("player", ignoreCase = true) })
    }

    @Test
    fun `a script built locally is not reported as a store run, and no message means no field`() {
        val json = report(store = false, stopMessage = null, end = RunEnd.MANUAL).toJson("v")
        assertEquals("local", json.get("source").asString)
        assertFalse(json.has("stopMessage"))
    }

    @Test
    fun `the stop message is the script's last line before it stopped`() {
        val stop = LocalDateTime.of(2026, 11, 4, 12, 0, 10)
        val lines = listOf(
            "[2026-11-04 12:00:02.100] [LividFarm] Out of runes - stopping.",
            "[2026-11-04 12:00:02.150] FIRED SYNTHETIC click at 100,200",
            "[2026-11-04 12:00:02.160] [Scripts] Stopped Livid Farm (LividFarm)",
            "[2026-11-04 12:00:02.170] LividFarm stopped.",
        )
        assertEquals("Out of runes - stopping.", RunReport.stopMessage(lines, stop, "LividFarm"))
    }

    @Test
    fun `an old or missing line is no stop message`() {
        val stop = LocalDateTime.of(2026, 11, 4, 12, 0, 30)
        assertNull(RunReport.stopMessage(listOf("[2026-11-04 12:00:02.100] [LividFarm] Out of runes"), stop, "LividFarm"))
        assertNull(RunReport.stopMessage(listOf("[2026-11-04 12:00:29.000] Refreshing AFK"), stop, "LividFarm"))
        assertNull(RunReport.stopMessage(emptyList(), stop, "LividFarm"))
    }

    @Test
    fun `a long stop message is cut to what the site keeps`() {
        val stop = LocalDateTime.of(2026, 11, 4, 12, 0, 1)
        val message = RunReport.stopMessage(listOf("[2026-11-04 12:00:00.500] ERROR: ${"x".repeat(400)}"), stop, "Any")
        assertEquals(300, message?.length)
    }
}
