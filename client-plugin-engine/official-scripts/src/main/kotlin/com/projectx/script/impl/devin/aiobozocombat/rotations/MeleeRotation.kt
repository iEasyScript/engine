package com.projectx.script.impl.devin.aiobozocombat.rotations

import com.projectx.script.impl.devin.aiobozocombat.CombatSnapshot
import com.projectx.script.impl.devin.aiobozocombat.RotationProvider
import com.projectx.script.impl.devin.aiobozocombat.RotationStep

/**
 * The melee damage priority, and the only rotation that currently exists.
 *
 * ⚠️ Unvalidated. The ordering follows PVME's post-Combat-Modernization advice, which was rebuilt
 * after the March 2026 rework and is still in flux, so treat it as a starting point to correct rather
 * than a solved rotation. The cooldowns and adrenaline values it runs on are read live from the
 * cache, which is the only part of it that is trusted.
 *
 * Rules are ordered in bands: spend the resource first (ultimates and thresholds gated on what they
 * consume), then build it (basics that generate bloodlust), then filler. Two orderings are easy to
 * get wrong and both produce a rotation that reads correctly and plays badly — a buff placed below
 * the ability it amplifies fires after its own payoff, and a generator placed above a spender keeps
 * generating into a full cap where the excess is discarded.
 *
 * A boss rotation belongs in its own subclass of [MeleePlan] plus its own rule list, and adds
 * survival and window bands above this core. None exists yet: reacting to a mechanic needs an attack,
 * animation or projectile signal, and none of those reaches [CombatSnapshot] — only an encounter
 * script can read them and push the result in.
 */
class MeleeRotation(private val params: MeleeParams = MeleeParams()) : RotationProvider {

    override fun upcoming(state: CombatSnapshot, depth: Int): List<RotationStep> =
        drive(MeleePlan(state, params), rules, depth)

    private val rules: List<Rule<MeleePlan>> = listOf(
        Rule(MeleeIds.BERSERK),
        Rule(MeleeIds.ASSAULT) { stacks >= params.spenderStacks },
        Rule(MeleeIds.FLURRY) {
            stacks >= params.spenderStacks && targetHealthAtMost(params.flurryHealthCeiling)
        },
        Rule(MeleeIds.METEOR_STRIKE) { berserking },
        Rule(MeleeIds.OVERPOWER),
        Rule(MeleeIds.PULVERISE),
        Rule(MeleeIds.HURRICANE) { stacks >= params.spenderStacks },

        // Chaos Roar amplifies the next hit, so it wants to land immediately before a spender rather
        // than compete with one.
        Rule(MeleeIds.CHAOS_ROAR) { stacks >= params.spenderStacks - 1 },

        Rule(MeleeIds.DISMEMBER),
        Rule(MeleeIds.PUNISH) { targetHealthAtMost(params.punishHealthCeiling) },
        Rule(MeleeIds.REND),
        Rule(MeleeIds.FURY),
        Rule(MeleeIds.BACKHAND),
        Rule(MeleeIds.ADAPTIVE_STRIKE),
        Rule(MeleeIds.BARGE)
    )
}
