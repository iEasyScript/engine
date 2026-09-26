package com.projectx.script.api

import org.projectx.core.game.combat.Ability
import org.projectx.core.game.combat.AbilityRegistry
import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.combat.CombatNames
import org.projectx.core.game.combat.Effect
import org.projectx.core.game.combat.EffectRegistry
import org.projectx.core.game.combat.EffectType

/**
 * Abilities, prayers and buffs by the name the game shows, for everything the [Ability] and [Effect] shortcuts do
 * not cover. Names match ignoring case, the non-breaking spaces the game writes into some of them ("Basic Attack"
 * finds "Basic<nbsp>Attack") and the underscores struct names use ("Scrimshaw Active" finds SCRIMSHAW_ACTIVE).
 *
 * Several abilities share a name (each style has its own Basic Attack, and Dive has variants), so the bar lookups
 * prefer the copy that is actually on an action bar.
 */
private fun matches(candidate: String, name: String) = CombatNames.matches(candidate, name)

/** The ability named [name] as it sits on the player's action bars, or null when it is not on one. */
fun actionBarAbility(name: String): AbilityType? = actionbarAbilities.keys.firstOrNull { matches(it.name, name) }

/** Whether an ability, prayer or curse named [name] is on one of the player's action bars. */
fun isOnActionBar(name: String): Boolean = actionBarAbility(name) != null

/** The ability named [name]: the action bar's copy when there is one, otherwise the first in the game's list. */
fun abilityNamed(name: String): AbilityType? = actionBarAbility(name) ?: AbilityRegistry.all.firstOrNull { matches(it.name, name) }

/**
 * The buff or debuff named [name] as the buff bar draws it, or null when the game has none by that name. The name
 * is matched the way Character > Effects shows it, so "Scrimshaw Active" finds the struct whether the cache spells
 * it SCRIMSHAW_ACTIVE or "Scrimshaw active".
 */
fun effectNamed(name: String): EffectType? = EffectRegistry.byDisplayName(name)

/** The buff or debuff read from struct [structId] - the Id column of Character > Effects - or null when unknown. */
fun effectWithId(structId: Int): EffectType? = EffectRegistry[structId]

/** Whether the buff or debuff named [name] is on the bar right now. False when the game has no such effect. */
fun effectActive(name: String): Boolean = effectNamed(name)?.active() == true

/** Whether the buff or debuff read from struct [structId] is on the bar right now. */
fun effectActive(structId: Int): Boolean = effectWithId(structId)?.active() == true

/** Milliseconds left on the buff or debuff named [name]; 0 when it is not up or the game gives it no timer. */
fun effectTimeRemainingMs(name: String): Long = effectNamed(name)?.timeRemainingMs() ?: 0L

/** Stacks on the buff or debuff named [name]; 0 when it is not up or it does not stack. */
fun effectStacks(name: String): Int = effectNamed(name)?.stacks() ?: 0

/** Every buff and debuff on the bar right now: the same list Character > Effects shows. */
fun activeEffects(): List<EffectType> =
    EffectRegistry.all.filter { runCatching { it.active() }.getOrDefault(false) }

/**
 * Ticks until the ability named [name] can be used again, counting the global cooldown unless [ignoreGCD]; 0 when
 * it is ready or the game has no ability by that name. Fractions are part-ticks, so 0.5 is 300 ms.
 */
@JvmOverloads
fun abilityCooldownTicks(name: String, ignoreGCD: Boolean = false): Double {
    val ability = abilityNamed(name) ?: return 0.0
    return if (ignoreGCD) ability.cooldownTicksIgnoreGCD() else ability.cooldownTicks()
}

/** Milliseconds until the ability named [name] can be used again; see [abilityCooldownTicks]. */
@JvmOverloads
fun abilityCooldownMillis(name: String, ignoreGCD: Boolean = false): Long =
    (abilityCooldownTicks(name, ignoreGCD) * AbilityType.MS_PER_TICK).toLong()

/** Whether the ability named [name] is cooling down. Only its own cooldown counts, not the global one. */
fun abilityOnCooldown(name: String): Boolean = abilityCooldownTicks(name, ignoreGCD = true) > AbilityType.OFF_CD_THRESHOLD

/** Clicks [ability] on the action bar. False when it is not on a bar or the click did not go through. */
fun castAbility(ability: AbilityType): Boolean = actionbarAbilities[ability]?.click(1) == true

/** Clicks the ability, prayer or curse named [name] on the action bar. False when it is not on a bar. */
fun castAbility(name: String): Boolean = actionBarAbility(name)?.let { castAbility(it) } == true

/** Whether the action-bar ability named [name] is ready, counting the global cooldown unless [ignoreGCD]. */
@JvmOverloads
fun abilityReady(name: String, ignoreGCD: Boolean = false): Boolean {
    val ability = actionBarAbility(name) ?: return false
    return if (ignoreGCD) ability.offCdIgnoreGCD() else ability.offCd()
}
