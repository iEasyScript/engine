package org.projectx.core.game.combat.npc

import kotlin.test.Test
import kotlin.test.assertEquals

class ResolvedNpcAnimationsTest {
    @Test
    fun `resolves seq gameval names to ids (preferred)`() {
        val r = ResolvedNpcAnimations.from(
            NpcAnimations(attack = "human_unarmedpunch", defend = "human_unarmedblock", death = "human_death"),
        )
        assertEquals(422, r.attack)
        assertEquals(424, r.defend)
        assertEquals(836, r.death)
    }

    @Test
    fun `falls back to a raw numeric id when the string is not a gameval name`() {
        val r = ResolvedNpcAnimations.from(NpcAnimations(attack = "424"))
        assertEquals(424, r.attack)
        assertEquals(ResolvedNpcAnimations.NO_ANIM, r.defend)
        assertEquals(ResolvedNpcAnimations.NO_ANIM, r.death)
    }

    @Test
    fun `null animations resolve to NONE`() {
        assertEquals(ResolvedNpcAnimations.NONE, ResolvedNpcAnimations.from(null))
    }
}
