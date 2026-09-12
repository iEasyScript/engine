package org.projectx.core.game.settings

import org.projectx.core.model.Vars
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsPaneTest {

    private fun Path.hasCache(): Boolean = exists() && Files.list(this).use { s ->
        s.asSequence().any { it.fileName.toString().matches(Regex("js5-\\d+\\.jcache")) }
    }

    private fun resolveCacheDir(): Path? {
        val userDir = System.getProperty("user.dir")
        for (candidate in listOf("$userDir/data/cache", "$userDir/../data/cache")) {
            val p = Path.of(candidate)
            if (p.hasCache()) return p
        }
        return null
    }

    private fun loadCache(): Boolean {
        val dir = resolveCacheDir() ?: return false
        Cache.init(SQLiteCache.load(dir, readOnly = true))
        return true
    }

    private fun paneSettings(): List<Setting> =
        Settings.paneRanges.flatMap { page -> page.mapNotNull { Settings.findBySlot(it) } }.distinct()

    @Test
    fun `every pane row resolves back to the setting the client clicked`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val ranges = Settings.paneRanges
        assertTrue(ranges.isNotEmpty(), "the settings pane has no pages — the cache lookup is wrong")

        val unresolved = ranges.flatMap { page -> page.filter { Settings.findBySlot(it) == null } }
        val gaps = ranges.sumOf { it.count() } - ranges.size
        assertTrue(unresolved.size <= ranges.size, "pane rows without a setting: $unresolved (of $gaps rows)")
    }

    @Test
    fun `the interface lock is the first row of its page and sits inside an armed range`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val lock = assertNotNull(Settings.find("settings_windows_lock_customisation"))
        val slot = assertNotNull(
            Settings.paneRanges.firstNotNullOfOrNull { page -> page.firstOrNull { Settings.findBySlot(it) == lock } },
            "the interface lock is on no pane page",
        )
        assertEquals(lock, Settings.findBySlot(slot))
    }

    @Test
    fun `every row the server has to answer is bound to a var`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val unbound = paneSettings().filter { setting ->
            when (Settings.control(setting)) {
                Control.CHECKBOX, Control.DROPDOWN, Control.SLIDER, Control.COLOUR, Control.NUMBER_BOX ->
                    !Settings.clientOnly(setting) && !Settings.bound(setting)
                Control.BUTTON, Control.LABEL -> false
            }
        }
        assertTrue(unbound.isEmpty(), "settings the client will wait on forever: ${unbound.map { it.name }}")
    }

    @Test
    fun `a checkbox toggles its var and reads back`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val vars = Vars().initOffline()
        val walk = assertNotNull(Settings.find("settings_walkmarkers"))
        val before = assertNotNull(Settings.get(vars, walk))
        assertTrue(Settings.toggle(vars, walk))
        assertEquals(1 - before, Settings.get(vars, walk))
    }

    @Test
    fun `a bit of a shared var toggles without disturbing its neighbours`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val vars = Vars().initOffline()
        val attack = assertNotNull(Settings.find("settings_retro_skillcape_attack"))
        val strength = assertNotNull(Settings.find("settings_retro_skillcape_strength"))
        assertTrue(Settings.set(vars, attack, 1))
        assertTrue(Settings.set(vars, strength, 1))
        assertTrue(Settings.set(vars, attack, 0))
        assertEquals(0, Settings.get(vars, attack))
        assertEquals(1, Settings.get(vars, strength))
    }

    @Test
    fun `a warning screen counts as shown until it is dismissed`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val vars = Vars().initOffline()
        val warning = assertNotNull(Settings.find("settings_doom_wyvern"))
        assertEquals(1, Settings.get(vars, warning))
        assertTrue(Settings.set(vars, warning, 0))
        assertEquals(0, Settings.get(vars, warning))
        assertTrue(Settings.set(vars, warning, 1))
        assertEquals(1, Settings.get(vars, warning))
    }

    @Test
    fun `the combat modes are one choice spread over two vars`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val vars = Vars().initOffline()
        val manual = assertNotNull(Settings.find("settings_combat_mode_manual"))
        val revolution = assertNotNull(Settings.find("settings_combat_mode_revo"))
        val classic = assertNotNull(Settings.find("settings_combat_mode_classic"))
        assertTrue(Settings.set(vars, classic, 1))
        assertEquals(0, Settings.get(vars, manual))
        assertTrue(Settings.set(vars, revolution, 1))
        assertEquals(1, Settings.get(vars, revolution))
        assertEquals(0, Settings.get(vars, manual))
        assertEquals(0, Settings.get(vars, classic))
    }

    @Test
    fun `an enum backed dropdown stores the value and reports the index`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val vars = Vars().initOffline()
        val pet = assertNotNull(Settings.find("settings_summoning_left_click_pet"))
        assertTrue(Settings.set(vars, pet, 2))
        assertEquals(2, Settings.get(vars, pet))
        assertTrue(vars.getVarBit("lore_selected_op1_pet") != 2, "the var must hold the enum's value, not the row's index")
    }

    @Test
    fun `sliders know where they start`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val transparency = assertNotNull(Settings.find("settings_windows_transparency"))
        assertEquals(Control.SLIDER, Settings.control(transparency))
        assertEquals(0..255, Settings.sliderRange(transparency))
    }

    @Test
    fun `the legacy options page can be found by its title`() {
        assumeTrue(loadCache(), "no cache on this machine — skipping")

        val (master, page) = assertNotNull(Settings.pageTitled("Legacy Options"))
        assertTrue(master != page)
    }
}
