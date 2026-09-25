package com.projectx.script.api

import com.projectx.game.input.Key
import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.combat.Effect
import java.util.function.BooleanSupplier

enum class CombatStyle { NECROMANCY, MELEE, RANGED, MAGIC }

/**
 * One step of a rotation. Build one with the factories ([ability], [inventory], [equip], [targetCycle],
 * [improvise], [custom]) and refine it with the `with`-style modifiers, each of which returns a new step:
 *
 * ```
 * val opener = listOf(
 *     RotationStep.ability("Bloat"),
 *     RotationStep.ability("Living Death").onlyIf { adrenaline >= 100 }.otherwise("Touch of Death"),
 *     RotationStep.inventory("Adrenaline renewal").waitTicks(1),
 *     RotationStep.improvise(CombatStyle.NECROMANCY, spend = true),
 * )
 * ```
 *
 * Every step waits after it fires before the next one may run: 3 server ticks unless told otherwise, or 1800 ms when
 * the step counts in milliseconds.
 */
class RotationStep private constructor(
    val label: String,
    val type: Type,
    val wait: Int,
    val waitInTicks: Boolean,
    internal val action: BooleanSupplier?,
    internal val style: CombatStyle?,
    internal val spend: Boolean,
    internal val targetCycleKey: Key?,
    internal val condition: BooleanSupplier?,
    internal val replacementLabel: String?,
    internal val replacementAction: BooleanSupplier?,
    internal val replacementWait: Int?,
    internal val continueAfterImprovise: Boolean,
) {
    enum class Type { ABILITY, INVENTORY, EQUIP, TARGET_CYCLE, IMPROVISE, CUSTOM }

    /** Waits [ticks] server ticks after this step fires. */
    fun waitTicks(ticks: Int) = copy(wait = ticks, waitInTicks = true)

    /** Waits [millis] milliseconds after this step fires. */
    fun waitMillis(millis: Int) = copy(wait = millis, waitInTicks = false)

    /** Fires only while [condition] holds; otherwise the replacement runs, or the step is skipped without waiting. */
    fun onlyIf(condition: BooleanSupplier) = copy(condition = condition)

    /** When the condition fails, casts the ability named [label] instead. Only meaningful on an ability step. */
    fun otherwise(label: String) = copy(replacementLabel = label)

    /** When the condition fails, runs [action] instead, reported as [label], then waits [waitTicks] if given. */
    @JvmOverloads
    fun otherwise(label: String, action: BooleanSupplier, waitTicks: Int? = null) =
        copy(replacementLabel = label, replacementAction = action, replacementWait = waitTicks)

    /** An improvise step normally repeats forever; this lets the rotation move past it after one cast. */
    fun continueAfterImprovise() = copy(continueAfterImprovise = true)

    private fun copy(
        wait: Int = this.wait,
        waitInTicks: Boolean = this.waitInTicks,
        condition: BooleanSupplier? = this.condition,
        replacementLabel: String? = this.replacementLabel,
        replacementAction: BooleanSupplier? = this.replacementAction,
        replacementWait: Int? = this.replacementWait,
        continueAfterImprovise: Boolean = this.continueAfterImprovise,
    ) = RotationStep(
        label, type, wait, waitInTicks, action, style, spend, targetCycleKey,
        condition, replacementLabel, replacementAction, replacementWait, continueAfterImprovise,
    )

    companion object {
        private const val DEFAULT_WAIT_TICKS = 3

        private fun of(
            label: String,
            type: Type,
            action: BooleanSupplier? = null,
            style: CombatStyle? = null,
            spend: Boolean = false,
            targetCycleKey: Key? = null,
        ) = RotationStep(
            label, type, DEFAULT_WAIT_TICKS, true, action, style, spend, targetCycleKey,
            null, null, null, null, false,
        )

        /** Casts the action-bar ability, prayer or curse named [name], but only once it is ready. */
        @JvmStatic
        fun ability(name: String) = of(name, Type.ABILITY)

        /** Uses the inventory item named [itemName] with its first option: drinks a potion, eats food. */
        @JvmStatic
        fun inventory(itemName: String) = of(itemName, Type.INVENTORY)

        /** Equips the inventory item named [itemName]. */
        @JvmStatic
        fun equip(itemName: String) = of(itemName, Type.EQUIP)

        /** Presses [key] to cycle to the next target. */
        @JvmStatic
        @JvmOverloads
        fun targetCycle(key: Key = Key.TAB) = of("Target cycle", Type.TARGET_CYCLE, targetCycleKey = key)

        /**
         * Casts whatever [style]'s filler logic picks for the moment, spending adrenaline and stacks when [spend]
         * is set. Repeats until the rotation is changed, unless [continueAfterImprovise] is applied.
         */
        @JvmStatic
        @JvmOverloads
        fun improvise(style: CombatStyle, spend: Boolean = false) = of("Improvise", Type.IMPROVISE, style = style, spend = spend)

        /** Runs [action]; it returns whether it did anything. */
        @JvmStatic
        fun custom(label: String, action: BooleanSupplier) = of(label, Type.CUSTOM, action = action)
    }
}

/**
 * Walks a rotation one step at a time without ever blocking: call [execute] every loop, alongside everything else
 * the script watches, and it fires the next step only once the previous step's wait has passed. Mechanics, food and
 * movement can therefore interrupt a rotation at any tick and it resumes where it left off.
 *
 * Ability steps never click an ability that is not ready, so a missing or cooling ability costs a step, not a stall.
 */
class RotationManager @JvmOverloads constructor(
    var loop: Boolean = false,
    var debug: Boolean = false,
) {
    class RecentStep(
        val label: String,
        val type: RotationStep.Type,
        val index: Int,
        val tick: Long,
        val conditionMet: Boolean,
        val succeeded: Boolean,
    )

    internal var currentTick: () -> Long = { ServerTick.count }
    internal var currentMillis: () -> Long = { System.currentTimeMillis() }

    var steps: List<RotationStep> = emptyList()
        private set

    /** Zero-based position of the step that runs next. */
    var index = 0
        private set

    private var cooldown = 0
    private var cooldownInTicks = true
    private var lastTick = 0L
    private var lastMillis = 0L
    private val recent = ArrayDeque<RecentStep>()

    /** The last few steps that fired, newest first. */
    val recentSteps: List<RecentStep> get() = recent.toList()

    val isFinished: Boolean get() = !loop && index >= steps.size

    val nextStep: RotationStep? get() = steps.getOrNull(index)

    /** Switches to [rotation], starting from its first step. Loading the rotation already running changes nothing. */
    fun load(rotation: List<RotationStep>) {
        if (rotation === steps) return
        steps = rotation
        reset()
        log("loaded ${rotation.size} steps")
    }

    fun unload() {
        steps = emptyList()
        index = 0
    }

    /** Back to the first step, ready to fire immediately. */
    fun reset() {
        index = 0
        cooldown = 0
        lastTick = 0L
        lastMillis = 0L
    }

    /** Continues from [position], firing it immediately. False when there is no such step. */
    fun jumpTo(position: Int): Boolean {
        if (position !in steps.indices) return false
        index = position
        cooldown = 0
        return true
    }

    /** Continues from the first step labelled [label], firing it immediately. */
    fun jumpTo(label: String): Boolean = jumpTo(steps.indexOfFirst { it.label == label })

    /** Fires the next step if its turn has come. Returns true when a step (or its replacement) fired. */
    fun execute(): Boolean {
        if (index >= steps.size) {
            if (!loop || steps.isEmpty()) return false
            reset()
        }
        if (!waitElapsed()) return false

        val step = steps[index]
        val conditionMet = try {
            step.condition?.asBoolean ?: true
        } catch (e: Throwable) {
            log("condition of '${step.label}' failed: ${e.message}")
            skip()
            return false
        }

        if (conditionMet) {
            record(step, step.label, conditionMet = true, succeeded = attempt(step.label) { perform(step) })
            startWait(step, useReplacementWait = false)
            return true
        }

        val replacement = step.replacementAction
        val replacementLabel = step.replacementLabel
        return when {
            replacement != null -> {
                val label = replacementLabel ?: step.label
                record(step, label, conditionMet = false, succeeded = attempt(label) { replacement.asBoolean })
                startWait(step, useReplacementWait = true)
                true
            }
            step.type == RotationStep.Type.ABILITY && replacementLabel != null -> {
                record(step, replacementLabel, conditionMet = false, succeeded = attempt(replacementLabel) { useAbility(replacementLabel) })
                startWait(step, useReplacementWait = false)
                true
            }
            else -> {
                skip()
                false
            }
        }
    }

    private fun perform(step: RotationStep): Boolean = when (step.type) {
        RotationStep.Type.ABILITY -> useAbility(step.label)
        RotationStep.Type.INVENTORY -> inventory.clickItem(step.label, 1)
        RotationStep.Type.EQUIP -> equipFromInventory(step.label)
        RotationStep.Type.TARGET_CYCLE -> step.targetCycleKey?.let { keyDown(it); keyUp(it); true } ?: false
        RotationStep.Type.IMPROVISE -> useAbility(improvise(step.style ?: CombatStyle.NECROMANCY, step.spend))
        RotationStep.Type.CUSTOM -> step.action?.asBoolean ?: false
    }

    private fun useAbility(name: String): Boolean = abilityUsable(name) && castAbility(name)

    private fun improvise(style: CombatStyle, spend: Boolean): String = when (style) {
        CombatStyle.NECROMANCY -> NecromancyImprovise.next(spend).also { log("improvised $it") }
        else -> BASIC_ATTACK
    }

    private inline fun attempt(label: String, block: () -> Boolean): Boolean = try {
        block()
    } catch (e: Throwable) {
        log("'$label' failed: ${e.message}")
        false
    }

    private fun waitElapsed(): Boolean =
        if (cooldownInTicks) currentTick() - lastTick >= cooldown else currentMillis() - lastMillis >= cooldown

    private fun startWait(step: RotationStep, useReplacementWait: Boolean) {
        val repeats = step.type == RotationStep.Type.IMPROVISE && !step.continueAfterImprovise
        if (!repeats) index++
        cooldown = if (useReplacementWait) step.replacementWait ?: step.wait else step.wait
        cooldownInTicks = step.waitInTicks
        lastTick = currentTick()
        lastMillis = currentMillis()
    }

    private fun skip() {
        cooldown = 0
        index++
    }

    private fun record(step: RotationStep, label: String, conditionMet: Boolean, succeeded: Boolean) {
        recent.addFirst(RecentStep(label, step.type, index, currentTick(), conditionMet, succeeded))
        while (recent.size > RECENT_STEPS) recent.removeLast()
        log("#$index $label -> ${if (succeeded) "fired" else "not fired"}")
    }

    private fun log(message: String) {
        if (debug) println("[Rotation] $message")
    }

    private companion object {
        const val RECENT_STEPS = 15
        const val BASIC_ATTACK = "Basic Attack"
    }
}

/**
 * Whether the action-bar ability named [name] can be cast right now: on a bar, its own cooldown within a tick of
 * ending, and enough adrenaline for it. The global cooldown is not counted, so a caller can queue into it.
 */
fun abilityUsable(name: String): Boolean {
    val ability = actionBarAbility(name) ?: return false
    return ability.cooldownTicksIgnoreGCD() <= 1.0 && adrenaline >= adrenalineCost(ability)
}

/**
 * The adrenaline, in percent, [ability] costs right now. The cache stores costs in tenths of a percent, and
 * Finger of Death is 10% cheaper for each necrosis stack it will consume.
 */
private fun adrenalineCost(ability: AbilityType): Double {
    val cost = ability.adrenalineReq / ADRENALINE_UNITS_PER_PERCENT
    if (ability.structId != FINGER_OF_DEATH_STRUCT) return cost
    val discount = Effect.NECROSIS.stacks.coerceAtMost(FINGER_MAX_NECROSIS) * ADRENALINE_PER_NECROSIS
    return (cost - discount).coerceAtLeast(0.0)
}

private const val ADRENALINE_UNITS_PER_PERCENT = 10.0
private const val FINGER_OF_DEATH_STRUCT = 48297
private const val FINGER_MAX_NECROSIS = 6
private const val ADRENALINE_PER_NECROSIS = 10.0

/** Equips the inventory item named [name] with whichever of Wield, Wear or Equip it offers. */
fun equipFromInventory(name: String): Boolean {
    val item = inventory.firstOrNull { it.name == name } ?: return false
    val option = item.invOps.firstOrNull { it == "Wield" || it == "Wear" || it == "Equip" } ?: return false
    return item.click(option)
}
