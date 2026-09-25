package com.projectx.script.api

import org.projectx.core.game.combat.Ability
import org.projectx.core.game.combat.AbilityRegistry
import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.combat.Effect
import org.projectx.core.game.combat.EffectRegistry
import org.projectx.core.game.combat.EffectType

/**
 * Abilities, prayers and buffs by the name the game shows, for everything the [Ability] and [Effect] shortcuts do
 * not cover. Names match ignoring case and the non-breaking
 * spaces the game writes into some of them ("Basic Attack" finds "Basic<nbsp>Attack").
 *
 * Several abilities share a name (each style has its own Basic Attack, and Dive has variants), so the bar lookups
 * prefer the copy that is actually on an action bar.
 */
private fun normalise(name: String) = name.replace("<nbsp>", " ").replace(' ', ' ').trim().lowercase()

private fun matches(candidate: String, name: String) = normalise(candidate) == normalise(name)

/** The ability named [name] as it sits on the player's action bars, or null when it is not on one. */
fun actionBarAbility(name: String): AbilityType? = actionbarAbilities.keys.firstOrNull { matches(it.name, name) }

/** Whether an ability, prayer or curse named [name] is on one of the player's action bars. */
fun isOnActionBar(name: String): Boolean = actionBarAbility(name) != null

/** The ability named [name]: the action bar's copy when there is one, otherwise the first in the game's list. */
fun abilityNamed(name: String): AbilityType? = actionBarAbility(name) ?: AbilityRegistry.all.firstOrNull { matches(it.name, name) }

/** The buff or debuff named [name], or null when the game has none by that name. */
fun effectNamed(name: String): EffectType? = EffectRegistry.all.firstOrNull { matches(it.name, name) }

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
