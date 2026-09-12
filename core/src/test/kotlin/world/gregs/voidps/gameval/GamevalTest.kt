package world.gregs.voidps.gameval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end sanity check for [Gameval]: it must load the gameval dictionaries live from the
 * `GAMEVAL_PATH` folder (`re-resources/gamevals/<type>.json`) and resolve ids to dev-names —
 * including the composite-keyed `component` type and graceful handling of misses.
 */
class GamevalTest {

    @Test
    fun `resolves names from the gameval folder`() {
        assertEquals("zaros_spellbook", Gameval.varbit(0))
        assertEquals("swarm_walk", Gameval.seq(0))
        assertEquals("hitsplat", Gameval.graphic(2))
        assertEquals("quickchat_listdialog_000", Gameval.varc(0))
        assertEquals("lastcastspell", Gameval.varp(0))
        assertEquals("100guide_eggs_overlay", Gameval.interfaceName(0))
    }

    @Test
    fun `label formats name with id, or bare id on miss`() {
        assertEquals("zaros_spellbook (0)", Gameval.varbitLabel(0))
        assertEquals("999999999", Gameval.varbitLabel(999999999))
    }

    @Test
    fun `component uses composite interface-colon-component key`() {
        assertEquals("100guide_eggs_overlay:100_q_anim5", Gameval.component(0, 1))
        assertTrue(Gameval.componentLabel(0, 1).contains("100guide_eggs_overlay:100_q_anim5"))
        assertEquals("123:456", Gameval.componentLabel(123, 456))
    }

    @Test
    fun `reverse lookup round-trips`() {
        assertEquals(0, Gameval.id(Gameval.VARBIT, "zaros_spellbook"))
        assertNull(Gameval.id(Gameval.VARBIT, "definitely_not_a_real_name"))
    }

    @Test
    fun `reverse component lookup resolves hash and id`() {
        assertEquals((906 shl 16) or 44, Gameval.requireComponentHash("lobbyscreen:playerinfo_panel"))
        assertEquals(44, Gameval.requireComponentId("lobbyscreen:playerinfo_panel"))
        assertNull(Gameval.componentHash("definitely:not_real"))
    }

    @Test
    fun `missing type degrades gracefully`() {
        assertNull(Gameval.name("not_a_real_type", 0))
        assertEquals("0", Gameval.label("not_a_real_type", 0))
        assertTrue(Gameval.has(Gameval.VARBIT))
        assertTrue(!Gameval.has("not_a_real_type"))
    }
}
