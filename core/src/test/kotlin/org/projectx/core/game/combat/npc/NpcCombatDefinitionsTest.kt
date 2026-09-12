package org.projectx.core.game.combat.npc

import org.projectx.core.game.combat.npc.drop.NpcDropTables
import world.gregs.voidps.cache.type.data.NpcType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies the example combat + drop resources load and the cache-base/JSON-override resolver merges. */
class NpcCombatDefinitionsTest {
    @Test
    fun `loads man combat definition`() {
        val man = NpcCombatDefinitions.byName["man"]
        assertNotNull(man, "man combat def should load from npc/combat/city/man.json")
        assertEquals(100, man.lifepoints)
        assertEquals(CombatStyle.MELEE, man.attackStyle)
    }

    @Test
    fun `resolve overlays JSON lifepoints over cache combat level`() {
        val type = NpcType(id = 1, name = "Man", combat = 2)
        val resolved = NpcCombatDefinitions.resolve(type)
        assertEquals(100, resolved.lifepoints)
        assertEquals(2, resolved.combatLevel)
        assertTrue(!resolved.estimatedLifepoints)
    }

    @Test
    fun `resolve falls back to default lifepoints when unauthored`() {
        val type = NpcType(id = 987654, name = "no-such-npc", combat = 5)
        val resolved = NpcCombatDefinitions.resolve(type)
        assertEquals(ResolvedNpcCombat.DEFAULT_LIFEPOINTS, resolved.lifepoints)
        assertTrue(resolved.estimatedLifepoints)
        assertEquals(5, resolved.combatLevel)
    }

    @Test
    fun `man drop table always yields coins`() {
        val def = NpcDropTables.byName["man"]
        assertNotNull(def, "man drop table should load from npc/drops/city/man.json")
        val drops = NpcDropTables.roll(def, Random(1))
        assertTrue(drops.any { it.name == "coins" }, "man's always-table should drop coins")
    }
}
