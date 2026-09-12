package com.projectx.script.impl.trent.combatutils

import org.projectx.core.game.combat.Ability
import org.projectx.core.game.combat.Effect
import com.projectx.script.Script
import com.projectx.script.api.*

suspend fun Script.optimizedNecromancyRotation() {
    val startAdren = adrenaline

    if (!Effect.LIVING_DEATH.active) {
        if (!Effect.SKELETON_WARRIOR.active)
            if (smartCast(
                    ability = Ability.CONJURE_UNDEAD_ARMY,
                    waitCondition = { Effect.SKELETON_WARRIOR.active }
                )) return
        if (Effect.VENGEFUL_GHOST.active && !Effect.VENGEFUL_GHOST_HAUNT.active)
            if (smartCast(
                    ability = Ability.COMMAND_VENGEFUL_GHOST,
                    waitCondition = { Effect.VENGEFUL_GHOST_HAUNT.active }
                )) return
        if (castAndWaitForCd(Ability.SOUL_SAP)) return
    }

    if (castWithAdren(Ability.DEATH_SKULLS, 60)) return

    if (Effect.SKELETON_WARRIOR.active)
        if (castAndWaitForCd(Ability.COMMAND_SKELETON_WARRIOR)) return

    if (!Effect.DEATH_ESSENCE_DEBUFF.active && Ability.LIVING_DEATH.cooldownMs >= 15000 && equipment.hasItem(Regex(".*omni guard.*", RegexOption.IGNORE_CASE)) && castWithAdren(Ability.WEAPON_SPECIAL_ATTACK, 80, { adrenaline < startAdren })) return

    if (Ability.LIVING_DEATH.cooldownMs >= 15000 && castWithAdren(Ability.BLOAT, 80, { adrenaline < startAdren })) return

    if (smartCast(Ability.LIVING_DEATH, 100, waitCondition = { Effect.LIVING_DEATH.active })) {
        val adrenBefore = adrenaline
        if (Effect.LIVING_DEATH.active && drinkAdrenPot())
            delayUntil(1000) { adrenaline > adrenBefore }
    }

    if (!Effect.LIVING_DEATH.active && Effect.NECROSIS.stacks >= 12) {
        if (equipment.hasItem(Regex(".*death guard.*", RegexOption.IGNORE_CASE))) {
            if (castWithAdren(Ability.WEAPON_SPECIAL_ATTACK, 30, { adrenaline < startAdren }))
                return
        } else {
            val currStacks = Effect.NECROSIS.stacks
            if (castWithEffectStacks(Ability.FINGER_OF_DEATH, Effect.NECROSIS, 6, { Effect.NECROSIS.stacks < currStacks }))
                return
        }
    }

    if (Effect.LIVING_DEATH.active) {
        val currStacks = Effect.NECROSIS.stacks
        if (castWithEffectStacks(Ability.FINGER_OF_DEATH, Effect.NECROSIS, 6, { Effect.NECROSIS.stacks < currStacks })) return
    }
    if (castWithEffectStacks(Ability.VOLLEY_OF_SOULS, Effect.RESIDUAL_SOUL, maxResidualSouls, { Effect.RESIDUAL_SOUL.stacks < maxResidualSouls }))
        return
    if (castWithEffectStacks(Ability.NECRO_BASIC_ATTACK, Effect.DEATH_SPARK, 5, { Effect.DEATH_SPARK.stacks < 5 }))
        return
    if (castAndWaitForCd(Ability.TOUCH_OF_DEATH)) return
    if (equipment.hasItem(Regex(".*omni guard.*", RegexOption.IGNORE_CASE))) {
        val currStacks = Effect.DEATH_SPARK.stacks
        if (smartCast(Ability.NECRO_BASIC_ATTACK, waitCondition = { currStacks != Effect.DEATH_SPARK.stacks }))
            return
    }
}

suspend fun Script.optimizedNecromancyRevo() {
    val startAdren = adrenaline

    if (castWithAdren(Ability.DEATH_SKULLS, 60)) return

    if (!Effect.DEATH_ESSENCE_DEBUFF.active && Ability.LIVING_DEATH.cooldownMs >= 15000 && equipment.hasItem(Regex(".*omni guard.*", RegexOption.IGNORE_CASE)) && castWithAdren(Ability.WEAPON_SPECIAL_ATTACK, 80, { adrenaline < startAdren })) return

    if (Ability.LIVING_DEATH.cooldownMs >= 15000 && castWithAdren(Ability.BLOAT, 80, { adrenaline < startAdren })) return

    if (castWithAdren(Ability.LIVING_DEATH, 100)) return

    if (!Effect.LIVING_DEATH.active && Effect.NECROSIS.stacks >= 12) {
        if (equipment.hasItem(Regex(".*death guard.*", RegexOption.IGNORE_CASE))) {
            if (castWithAdren(Ability.WEAPON_SPECIAL_ATTACK, 30, { adrenaline < startAdren }))
                return
        } else {
            val currStacks = Effect.NECROSIS.stacks
            if (castWithEffectStacks(Ability.FINGER_OF_DEATH, Effect.NECROSIS, 6, { Effect.NECROSIS.stacks < currStacks }))
                return
        }
    }

    if (Effect.LIVING_DEATH.active) {
        val currStacks = Effect.NECROSIS.stacks
        if (castWithEffectStacks(Ability.FINGER_OF_DEATH, Effect.NECROSIS, 6, { Effect.NECROSIS.stacks < currStacks })) return
    }
    if (castWithEffectStacks(Ability.VOLLEY_OF_SOULS, Effect.RESIDUAL_SOUL, maxResidualSouls, {
            Effect.RESIDUAL_SOUL.stacks < maxResidualSouls
        }))
        return
}