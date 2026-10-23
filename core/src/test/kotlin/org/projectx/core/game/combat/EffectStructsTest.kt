package org.projectx.core.game.combat

import world.gregs.voidps.gameval.Gameval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A buff the engine has no struct for cannot be found by name or read at all, so every struct Jagex names as a
 * buff or debuff must be listed. A client update that adds one fails here until it is added.
 */
class EffectStructsTest {

    @Test
    fun `every struct named as a buff or debuff is known`() {
        val named = Gameval.entries(Gameval.STRUCT).filterValues { BUFF_NAME.containsMatchIn(it) }
        assertTrue(named.isNotEmpty(), "no buff struct names loaded from the gamevals")
        val missing = named.filterKeys { it !in EffectStructs.ALL.toSet() }.map { (id, name) -> "$id $name" }
        assertEquals(emptyList(), missing)
    }

    private companion object {
        val BUFF_NAME = Regex("^combatv2_(de)?buff_")
    }
}
