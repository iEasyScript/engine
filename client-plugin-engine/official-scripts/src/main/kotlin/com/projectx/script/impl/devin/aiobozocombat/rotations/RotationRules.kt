package com.projectx.script.impl.devin.aiobozocombat.rotations

import com.projectx.script.impl.devin.aiobozocombat.CombatSnapshot
import com.projectx.script.impl.devin.aiobozocombat.RotationAction
import com.projectx.script.impl.devin.aiobozocombat.RotationStep
import com.projectx.script.impl.devin.aiobozocombat.Urgency
import com.projectx.script.impl.devin.aiobozocombat.projectionKey
import world.gregs.voidps.gameval.Gameval

/**
 * One entry in a rotation's priority list.
 *
 * [gate] reads the projected plan rather than live state, so it sees the resources the steps above it
 * already spent. A prohibition — an ability that must not be pressed under some condition — has no
 * layer of its own and can only be expressed by repeating the test on every rule it touches, so it
 * belongs behind one named property on the plan. Inlining the same test twice is how one copy gets
 * missed, and a missing copy is invisible in review.
 */
class Rule<P : RotationPlan>(
    val action: RotationAction,
    val urgency: Urgency = Urgency.ROTATION,
    val reason: String? = null,
    val gate: P.() -> Boolean = { true }
) {
    constructor(
        ability: Int,
        urgency: Urgency = Urgency.ROTATION,
        reason: String? = null,
        gate: P.() -> Boolean = { true }
    ) : this(RotationAction.Ability(ability), urgency, reason, gate)
}

/** Ask for a prayer to be in a state. Satisfied — and therefore silent — once it already is. */
fun pray(structId: Int, active: Boolean = true): RotationAction = RotationAction.Prayer(structId, active)

/** Eat, drink or throw one specific item from the backpack. */
fun use(itemId: Int): RotationAction = RotationAction.Item(itemId)

/** Use whatever the player carries from a dose- or tier-split family: `consume("Brew", "brew")`. */
fun consume(label: String, nameFragment: String): RotationAction =
    RotationAction.Consumable(label, nameFragment)

/** Tell the player to do something that is not a press. */
fun cue(label: String): RotationAction = RotationAction.Cue(label)

/** The mutable projection a rule list is walked against. */
interface RotationPlan {
    fun usable(action: RotationAction): Boolean
    fun apply(action: RotationAction)
}

/** First match wins, then the plan advances so the next step is chosen against a different state. */
fun <P : RotationPlan> drive(plan: P, rules: List<Rule<P>>, depth: Int): List<RotationStep> = buildList {
    repeat(depth) {
        val rule = rules.firstOrNull { plan.usable(it.action) && it.gate(plan) } ?: return@buildList
        add(RotationStep(rule.action, rule.urgency, rule.reason))
        plan.apply(rule.action)
    }
}

/**
 * Numbers a rotation guide states and the game does not hold. Anything the game does hold is read
 * live instead, because a parameterised copy of a readable value goes stale while the real one is
 * right there.
 *
 * Defaults are chosen by which failure is cheaper rather than by which is likeliest. A spender gated
 * above the resource cap never fires at all and the rotation stalls; gated below it, it fires early
 * and loses a little damage. So thresholds sit low enough to still trigger.
 */
class MeleeParams(
    val spenderStacks: Int = 4,
    val flurryHealthCeiling: Double = 71.0,
    val punishHealthCeiling: Double = 50.0
)

/**
 * The melee resource model a rule list is projected against: adrenaline, bloodlust stacks and elapsed
 * time across the queue. Encounter state belongs on a subclass.
 *
 * Deliberately coarse — it tracks what the gates read and nothing else, because deeper fidelity would
 * imply confidence the model has not earned.
 */
open class MeleePlan(val state: CombatSnapshot, val params: MeleeParams) : RotationPlan {

    var adrenaline: Double = state.adrenaline
    var stacks: Int = state.bloodlustStacks.coerceAtLeast(0)
    var berserking: Boolean = state.bloodlustEmpowered

    private var elapsed = 0
    private val spent = HashSet<String>()
    private val projectedPrayers = HashMap<Int, Boolean>()

    /**
     * Unknown target health answers false rather than assuming a full bar. The obvious workaround of
     * defaulting to 100 makes a rule fire at the wrong time instead of not at all, which is worse.
     */
    fun targetHealthAtMost(percent: Double): Boolean =
        state.targetHealthPercent?.let { it <= percent } == true

    fun targetHealthAtLeast(percent: Double): Boolean =
        state.targetHealthPercent?.let { it >= percent } == true

    /** True while the prayer is on, accounting for any switch already predicted earlier in the queue. */
    fun praying(structId: Int): Boolean =
        projectedPrayers[structId] ?: state.prayerActive(structId)

    fun holds(itemId: Int): Boolean = state.hasItem(itemId)

    /**
     * An action may appear at most once per queue.
     *
     * The snapshot carries an ability's *live* remaining cooldown, which keeps reading zero after the
     * projection has spent it because nothing was really cast. Without the used-once set the same
     * basic wins every remaining step and the queue renders as one ability repeated. The cost is that
     * a basic which genuinely recurs inside the lookahead is shown only once.
     */
    override fun usable(action: RotationAction): Boolean {
        if (action.projectionKey in spent) return false
        if (!outstanding(action)) return false
        val structId = (action as? RotationAction.Ability)?.structId
            ?: return state.performable(action)
        val ability = state.ability(structId) ?: return false
        if (ability.cooldownTicks > elapsed) return false
        return adrenaline >= ability.adrenalineRequired
    }

    private fun outstanding(action: RotationAction): Boolean = when (action) {
        is RotationAction.Prayer -> praying(action.structId) != action.active
        else -> state.outstanding(action)
    }

    /**
     * Only an ability consumes the global cooldown. A prayer switch, a brew and a sidestep all happen
     * alongside the rotation rather than instead of it, so advancing the clock for them would push
     * every predicted ability a tick later than it really lands.
     */
    override fun apply(action: RotationAction) {
        spent += action.projectionKey
        if (action is RotationAction.Prayer) {
            projectedPrayers[action.structId] = action.active
            return
        }
        val structId = (action as? RotationAction.Ability)?.structId ?: return
        applyAbility(structId)
    }

    private fun applyAbility(structId: Int) {
        val ability = state.ability(structId) ?: return
        elapsed += maxOf(GLOBAL_COOLDOWN_TICKS, ability.channelTicks)
        adrenaline = (adrenaline - ability.adrenalineCost + ability.adrenalineGenerated)
            .coerceIn(0.0, MAX_ADRENALINE)
        when (structId) {
            MeleeIds.BERSERK -> {
                berserking = true
                stacks = (stacks + BERSERK_STACKS).coerceAtMost(BERSERK_CAP)
            }
            in SPENDERS -> stacks = (stacks - params.spenderStacks).coerceAtLeast(0)
            in GENERATORS -> {
                val cap = if (berserking) BERSERK_CAP else BASE_CAP
                stacks = (stacks + GENERATORS.getValue(structId)).coerceAtMost(cap)
            }
        }
    }

    private companion object {
        const val GLOBAL_COOLDOWN_TICKS = 3
        const val MAX_ADRENALINE = 100.0
        const val BASE_CAP = 4
        const val BERSERK_CAP = 8

        /** Berserk raises the cap to eight for its own duration and grants four on cast. */
        const val BERSERK_STACKS = 4

        val SPENDERS = setOf(MeleeIds.ASSAULT, MeleeIds.FLURRY, MeleeIds.HURRICANE)

        val GENERATORS = mapOf(
            MeleeIds.REND to 2,
            MeleeIds.PUNISH to 1,
            MeleeIds.BACKHAND to 1,
            MeleeIds.FURY to 1,
            MeleeIds.ADAPTIVE_STRIKE to 1,
            MeleeIds.BARGE to 1,
            MeleeIds.CHAOS_ROAR to 1
        )
    }
}

/**
 * The melee book, resolved by dev-name at runtime because ids move between builds.
 *
 * The slugs predate EOC 2.0 and do not match what the game calls these abilities, so they cannot be
 * inferred from the in-game name — Adaptive Strike is `attack_sever`, Barge is `attack_charge`,
 * Hurricane is `attack_whirlwind`, Rend is `attack_adaptive_sweep`, Provoke is `defence_taunt` and
 * Escape is `ranged_escape`. Check any new name against `re-resources/gamevals/gameval.py` rather
 * than guessing: a plausible guess resolves to null and fails silently at runtime, never at compile
 * time.
 */
object MeleeIds {
    val BERSERK = struct("combatv2_ability_strength_berserk")
    val ASSAULT = struct("combatv2_ability_strength_assault")
    val FLURRY = struct("combatv2_ability_attack_flurry")
    val HURRICANE = struct("combatv2_ability_attack_whirlwind")
    val METEOR_STRIKE = struct("combatv2_ability_attack_meteor_strike")
    val OVERPOWER = struct("combatv2_ability_attack_overpower")
    val PULVERISE = struct("combatv2_ability_strength_pulverise")
    val CHAOS_ROAR = struct("combatv2_ability_strength_chaos_roar")
    val DISMEMBER = struct("combatv2_ability_strength_dismember")
    val PUNISH = struct("combatv2_ability_strength_punish")
    val REND = struct("combatv2_ability_attack_adaptive_sweep")
    val FURY = struct("combatv2_ability_strength_fury")
    val BACKHAND = struct("combatv2_ability_attack_backhand")
    val ADAPTIVE_STRIKE = struct("combatv2_ability_attack_sever")
    val BARGE = struct("combatv2_ability_attack_charge")

    val RESONANCE = struct("combatv2_ability_defence_resonance")
    val DEVOTION = struct("combatv2_ability_defence_devotion")
    val DEBILITATE = struct("combatv2_ability_defence_debilitate")
    val FREEDOM = struct("combatv2_ability_defence_freedom")
    val ANTICIPATION = struct("combatv2_ability_defence_anticipation")
    val PROVOKE = struct("combatv2_ability_defence_taunt")
    val SURGE = struct("combatv2_ability_magic_surge")
    val ESCAPE = struct("combatv2_ability_ranged_escape")
}

/**
 * Prayers, in both books.
 *
 * A rotation cannot know which book the player is on, so it asks for the curse and the standard
 * prayer as separate steps with the same gate and lets the bar decide: whichever is not barred fails
 * to be performable and never renders.
 */
object PrayerIds {
    val DEFLECT_MELEE = struct("combatv2_curse_deflect_melee")
    val DEFLECT_MISSILES = struct("combatv2_curse_deflect_missiles")
    val DEFLECT_MAGIC = struct("combatv2_curse_deflect_magic")
    val SOUL_SPLIT = struct("combatv2_curse_soulsplit")

    val PROTECT_MELEE = struct("combatv2_prayer_protect_melee")
    val PROTECT_MISSILES = struct("combatv2_prayer_protect_missiles")
    val PROTECT_MAGIC = struct("combatv2_prayer_protect_magic")
}

/** An unresolved name yields an id no bar can hold, so the rule using it is skipped rather than wrong. */
fun struct(name: String): Int {
    val id = Gameval.id(Gameval.STRUCT, name)
    if (id == null) println("[Rotation] unresolved struct $name")
    return id ?: -1
}
