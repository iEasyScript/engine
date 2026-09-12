package org.projectx.core.game.settings

import org.projectx.core.model.Vars
import org.junit.jupiter.api.Assumptions.assumeTrue
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.sqlite.SQLiteCache
import world.gregs.voidps.gameval.Gameval
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsTest {

    @Test
    fun `model loads with every category and sub-organisation`() {
        assertEquals(12, Settings.categories.size)
        assertTrue(Settings.real.size >= 500, "expected the full settings set, got ${Settings.real.size}")
        assertTrue(Settings.all.size > Settings.real.size, "separators should be present in all but not real")
        for (cat in Settings.categories) {
            assertNotNull(Settings.byCategory[cat], "category $cat has no sub-pages")
        }
        val lock = Settings.find("windows_lock_customisation")
        assertNotNull(lock)
        assertEquals(SettingType.TOGGLE, lock.type)
        assertEquals("varbit", lock.varDomain)
    }

    @Test
    fun `every settable setting has a resolvable backing var and round-trips through Vars`() {
        CacheFixture.requireCache()

        val settable = Settings.real.filter { it.settable }
        assertTrue(settable.size >= 300, "expected many settable settings, got ${settable.size}")

        val unresolved = settable.filter {
            val domain = if (it.domain == VarDomain.VARBIT) Gameval.VARBIT else Gameval.VAR_PLAYER
            Gameval.id(domain, it.backingVar!!) == null
        }
        assertTrue(unresolved.isEmpty(), "unresolved backing vars: ${unresolved.take(15).map { it.name to it.backingVar }}")

        val vars = Vars()
        var checked = 0
        for (s in settable.filter { it.type == SettingType.TOGGLE }.take(50)) {
            assertTrue(Settings.set(vars, s, 1), "set failed for ${s.name}")
            assertEquals(1, Settings.get(vars, s), "round-trip failed for ${s.name} (${s.backingVar})")
            checked++
        }
        assertTrue(checked > 0)
    }
}
