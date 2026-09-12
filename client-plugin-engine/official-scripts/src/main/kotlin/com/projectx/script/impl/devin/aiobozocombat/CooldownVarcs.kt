package com.projectx.script.impl.devin.aiobozocombat

import org.projectx.core.game.combat.AbilityCooldownVarcs
import org.projectx.core.game.combat.CombatIds
import world.gregs.voidps.gameval.Gameval

/**
 * Derives an ability's cooldown varcs from its struct slug instead of a hand-authored table, and
 * reports how the derivation compares against the table currently in `:core`.
 *
 * The convention is `combatv2_ability_<slug>` to `combatv2_cooldown_<slug>_end_client`, with a short
 * exception table for the slugs that drifted. That covers every cooldown varc rather than the subset
 * `:core` hardcodes — a missing entry there silently drops the ability from the action feed, and the
 * gap included every defensive.
 */
object CooldownVarcs {
    private const val ABILITY_PREFIX = "combatv2_ability_"
    private const val COOLDOWN_PREFIX = "combatv2_cooldown_"

    private val slugOverrides = mapOf(
        "magic_dragonbreath" to "magic_dragon_breath",
        "magic_magma_tempest_revolution" to "magic_magma_tempest",
        "strength_dismember" to "melee_dismember",
        "attack_dive" to "attack_bladed_dive"
    )

    private val cache = HashMap<Int, Resolution>()

    class Resolution(
        val structId: Int,
        val derivedEnd: Int,
        val derivedStart: Int,
        val hardcodedEnd: Int
    ) {
        val agrees: Boolean get() = derivedEnd != -1 && derivedEnd == hardcodedEnd
        val gapFilled: Boolean get() = derivedEnd != -1 && hardcodedEnd == -1
        val unresolved: Boolean get() = derivedEnd == -1 && hardcodedEnd == -1

        val status: String
            get() = when {
                agrees -> "ok"
                gapFilled -> "NEW"
                derivedEnd == -1 && hardcodedEnd != -1 -> "table-only"
                unresolved -> "none"
                else -> "CONFLICT"
            }
    }

    @Synchronized
    fun resolve(structId: Int): Resolution = cache.getOrPut(structId) {
        val slug = Gameval.struct(structId)?.removePrefix(ABILITY_PREFIX)
            ?.let { slugOverrides[it] ?: it }
        Resolution(
            structId = structId,
            derivedEnd = varc(slug, "end"),
            derivedStart = varc(slug, "start"),
            hardcodedEnd = AbilityCooldownVarcs.endVarc(structId)
        )
    }

    fun endVarc(structId: Int): Int = resolve(structId).let {
        if (it.derivedEnd != -1) it.derivedEnd else it.hardcodedEnd
    }

    /**
     * Ticks until [structId] comes off its own cooldown, ignoring the global cooldown.
     *
     * `AbilityType.cooldownTicksIgnoreGCD` cannot be used for this: it resolves through `:core`'s
     * hardcoded table and answers 0.0 — "ready" — for any ability the table is missing, which on a
     * live bar is every defensive.
     */
    fun remainingTicks(structId: Int, nowCycle: Int, readVarc: (Int) -> Int): Double {
        val varc = endVarc(structId)
        if (varc == -1) return 0.0
        val end = runCatching { readVarc(varc) }.getOrNull() ?: return 0.0
        return if (end > nowCycle) (end - nowCycle).toDouble() / CombatIds.CYCLES_PER_TICK else 0.0
    }

    private fun varc(slug: String?, edge: String): Int {
        if (slug == null) return -1
        return Gameval.id(Gameval.VAR_CLIENT, "$COOLDOWN_PREFIX${slug}_${edge}_client") ?: -1
    }
}
