package org.projectx.core.game.combat.npc

import world.gregs.voidps.cache.type.data.NpcType
import world.gregs.voidps.gameval.Gameval

/**
 * The effective combat profile for an NPC: the cache-derived base overlaid with any authored
 * [NpcCombatDefinition] overrides. Detailed stats (affinity/armour/damage/accuracy/weakness) are
 * authored for now; wiring them from cache `params` is a follow-up (the exact combat param ids need
 * confirmation). Combat level comes from the cache field; lifepoints has no cache source and is
 * authored, defaulting to [DEFAULT_LIFEPOINTS] (flagged via [estimatedLifepoints]).
 */
data class ResolvedNpcCombat(
    val lifepoints: Int,
    val combatLevel: Int,
    val attackStyle: CombatStyle?,
    val maxHit: Int?,
    val attackSpeed: Int?,
    val attackRange: Int?,
    val stats: NpcCombatStats?,
    val aggression: NpcAggression?,
    val animations: ResolvedNpcAnimations,
    val sounds: NpcSounds?,
    val respawnTicks: Int?,
    val headbarType: Int,
    val hideHitpoints: Boolean,
    val estimatedLifepoints: Boolean,
) {
    companion object {
        const val DEFAULT_LIFEPOINTS = 100
        private const val DEFAULT_HEADBAR = 0
        private const val HEADBAR_GAMEVAL = "headbar"

        fun from(type: NpcType, def: NpcCombatDefinition?): ResolvedNpcCombat {
            val combatLevel = def?.combatLevel ?: type.combat
            val lifepoints = def?.lifepoints
            val headbarType = def?.headbar?.let { Gameval.id(HEADBAR_GAMEVAL, it) } ?: DEFAULT_HEADBAR
            return ResolvedNpcCombat(
                lifepoints = lifepoints ?: DEFAULT_LIFEPOINTS,
                combatLevel = combatLevel,
                attackStyle = def?.attackStyle,
                maxHit = def?.maxHit,
                attackSpeed = def?.attackSpeed,
                attackRange = def?.attackRange,
                stats = def?.stats,
                aggression = def?.aggression,
                animations = ResolvedNpcAnimations.from(def?.animations),
                sounds = def?.sounds,
                respawnTicks = def?.respawnTicks,
                headbarType = headbarType,
                hideHitpoints = def?.hideHitpoints ?: false,
                estimatedLifepoints = lifepoints == null,
            )
        }
    }
}

/** Combat seq ids resolved from authored [NpcAnimations] names; [NO_ANIM] (−1) when unset/unknown. */
data class ResolvedNpcAnimations(val attack: Int, val defend: Int, val death: Int) {
    companion object {
        const val NO_ANIM = -1
        val NONE = ResolvedNpcAnimations(NO_ANIM, NO_ANIM, NO_ANIM)

        fun from(anims: NpcAnimations?): ResolvedNpcAnimations {
            if (anims == null) return NONE
            return ResolvedNpcAnimations(seq(anims.attack), seq(anims.defend), seq(anims.death))
        }

        /** Prefer a seq gameval name; fall back to a raw numeric id; [NO_ANIM] if unset/unknown. */
        private fun seq(nameOrId: String?): Int {
            if (nameOrId.isNullOrBlank()) return NO_ANIM
            return Gameval.id(Gameval.SEQ, nameOrId) ?: nameOrId.toIntOrNull() ?: NO_ANIM
        }
    }
}
