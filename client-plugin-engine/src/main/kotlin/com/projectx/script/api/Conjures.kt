package com.projectx.script.api

import com.projectx.script.Script
import com.projectx.util.gaussian

private const val CONJURE_ARMY = "Conjure Undead Army"

private val CONJURES = listOf("Skeleton Warrior", "Vengeful Ghost", "Putrid Zombie")

/** Whether all three Necromancy conjures are standing. */
val conjuresUp: Boolean
    get() = CONJURES.all { effectNamed(it)?.active() == true }

/**
 * Conjures the undead army and waits for all three to stand, so the caller can hold its state until they are
 * ready. Returns true once they are up, and false when the ability could not be used.
 */
suspend fun Script.summonConjures(): Boolean {
    if (conjuresUp) return true
    if (!abilityUsable(CONJURE_ARMY) || !castAbility(CONJURE_ARMY)) return false
    delayUntil(gaussian(6000L, 1200L)) { conjuresUp }
    return conjuresUp
}
