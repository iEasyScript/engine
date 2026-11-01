package com.projectx.script.api

import java.util.function.BooleanSupplier
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrayerThreatTest {

    @Test
    fun `a condition threat calls for its prayer only while the condition holds`() {
        var live = false
        val threat = PrayerThreat.condition("Enrage", Prayer.DEFLECT_RANGE, BooleanSupplier { live })

        assertNull(threat.currentPrayer())
        live = true
        assertEquals(Prayer.DEFLECT_RANGE, threat.currentPrayer())
        assertEquals(listOf(Prayer.DEFLECT_RANGE), threat.prayers)
    }

    @Test
    fun `a bypassed threat calls for nothing`() {
        var bypassed = true
        val threat = PrayerThreat.condition("Slam", Prayer.DEFLECT_MELEE, BooleanSupplier { true })
            .unless(BooleanSupplier { bypassed })

        assertNull(threat.currentPrayer())
        bypassed = false
        assertEquals(Prayer.DEFLECT_MELEE, threat.currentPrayer())
    }

    @Test
    fun `a dynamic threat follows its choice and keeps its settings through copies`() {
        var choice: Prayer? = null
        val threat = PrayerThreat.dynamic("Jads", listOf(Prayer.DEFLECT_MAGIC, Prayer.DEFLECT_RANGE), Supplier { choice })
            .priority(15)
            .durationTicks(4)

        assertNull(threat.currentPrayer())
        choice = Prayer.DEFLECT_RANGE
        assertEquals(Prayer.DEFLECT_RANGE, threat.currentPrayer())
        choice = Prayer.DEFLECT_MAGIC
        assertEquals(Prayer.DEFLECT_MAGIC, threat.currentPrayer())
        assertEquals(Prayer.DEFLECT_MAGIC, threat.prayer)
        assertEquals(listOf(Prayer.DEFLECT_MAGIC, Prayer.DEFLECT_RANGE), threat.prayers)
        assertEquals(15, threat.priority)
        assertEquals(4, threat.durationTicks)
    }

    @Test
    fun `a dynamic threat ignores a prayer outside its candidates`() {
        val threat = PrayerThreat.dynamic("Jads", listOf(Prayer.DEFLECT_MAGIC), Supplier { Prayer.SOUL_SPLIT })

        assertNull(threat.currentPrayer())
    }

    @Test
    fun `a dynamic threat needs a prayer to choose from`() {
        assertFailsWith<IllegalArgumentException> { PrayerThreat.dynamic("None", emptyList(), Supplier { null }) }
    }

    @Test
    fun `protection prayers follow the prayer book`() {
        assertEquals(Prayer.PROTECT_MELEE, protectionPrayerFor(CombatStyle.MELEE, curses = false))
        assertEquals(Prayer.PROTECT_RANGED, protectionPrayerFor(CombatStyle.RANGED, curses = false))
        assertEquals(Prayer.PROTECT_MAGIC, protectionPrayerFor(CombatStyle.MAGIC, curses = false))
        assertEquals(Prayer.PROTECT_NECROMANCY, protectionPrayerFor(CombatStyle.NECROMANCY, curses = false))
        assertEquals(Prayer.DEFLECT_MELEE, protectionPrayerFor(CombatStyle.MELEE, curses = true))
        assertEquals(Prayer.DEFLECT_RANGE, protectionPrayerFor(CombatStyle.RANGED, curses = true))
        assertEquals(Prayer.DEFLECT_MAGIC, protectionPrayerFor(CombatStyle.MAGIC, curses = true))
        assertEquals(Prayer.DEFLECT_NECROMANCY, protectionPrayerFor(CombatStyle.NECROMANCY, curses = true))
    }
}
