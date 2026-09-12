package com.projectx.script.impl.devin.aiobozocombat

import org.projectx.core.game.combat.AbilityType
import org.projectx.core.game.combat.CombatIds
import kotlin.math.abs

class FeedEvent(
    val cycle: Int,
    val tick: Int,
    val label: String,
    val detail: String,
    /** Set only for observed casts, so the queue can resolve the ability's icon and keybind. */
    val structId: Int? = null,
    /** Set instead of [structId] for item uses, which have no ability struct. */
    val itemId: Int? = null,
    val displayName: String? = null
)

private class PendingCost(val structId: Int, val name: String, val before: Double, val declared: Double)

/**
 * Polls the signals a cast is visible through and records every change as a timestamped event.
 *
 * The point is to observe what actually fires rather than to confirm what was expected, so nothing
 * here filters on a predicted rotation. Watching the per-ability cooldown varcs makes this
 * source-agnostic by construction: a manual click, a keybind press and a revolution auto-cast all
 * move the same varc, so revolution needs no special handling.
 */
class ActionFeed(private val capacity: Int = 60) {
    private val lastVarcValue = HashMap<Int, Int>()
    private val events = ArrayDeque<FeedEvent>()
    private var lastStacks = Int.MIN_VALUE
    private var lastEmpowered: Boolean? = null
    private var lastAdrenaline = Double.NaN
    private var pendingCost: PendingCost? = null

    @Synchronized
    fun snapshot(): List<FeedEvent> = events.toList()

    /** Most recent observed casts, oldest first, for the used side of the queue. */
    @Synchronized
    fun recentCasts(limit: Int): List<Int> =
        events.mapNotNull { it.structId }.takeLast(limit)

    /** Recent actions of any kind — abilities, prayers and item uses — oldest first. */
    @Synchronized
    fun recentActions(limit: Int): List<FeedEvent> =
        events.filter { it.structId != null || it.itemId != null }.takeLast(limit)

    @Synchronized
    fun clear() {
        events.clear()
        lastVarcValue.clear()
        lastStacks = Int.MIN_VALUE
        lastEmpowered = null
        lastAdrenaline = Double.NaN
    }

    /**
     * [itemId] and [name] are set instead of [structId] for the actions with no ability struct —
     * prayers and consumables, which no cooldown varc can see.
     */
    @Synchronized
    fun record(
        cycle: Int,
        label: String,
        detail: String,
        structId: Int? = null,
        itemId: Int? = null,
        name: String? = null
    ) {
        events.addLast(FeedEvent(cycle, cycle / CYCLES_PER_TICK, label, detail, structId, itemId, name))
        while (events.size > capacity) events.removeFirst()
        println("[BozoCap] cycle=$cycle tick=${cycle / CYCLES_PER_TICK} $label | $detail")
    }

    fun pollAbilities(abilities: Collection<AbilityType>, cycle: Int, readVarc: (Int) -> Int) {
        for (ability in abilities) {
            val resolution = CooldownVarcs.resolve(ability.structId)
            pollVarc(resolution.derivedStart, cycle, ability, "start", readVarc)
            pollVarc(resolution.derivedEnd, cycle, ability, "end", readVarc, seedsQueue = false)
        }
    }

    private fun pollVarc(
        varc: Int,
        cycle: Int,
        ability: AbilityType,
        edge: String,
        readVarc: (Int) -> Int,
        seedsQueue: Boolean = true
    ) {
        if (varc == -1) return
        val value = runCatching { readVarc(varc) }.getOrNull() ?: return
        val previous = lastVarcValue.put(varc, value)
        if (previous == null || previous == value) return
        val name = ability.name.ifBlank { "struct ${ability.structId}" }
        if (seedsQueue) {
            val declared = runCatching { ability.adrenalineReq / 10.0 }.getOrDefault(0.0)
            pendingCost = PendingCost(ability.structId, name, lastAdrenaline, declared)
        }
        record(cycle, "cast? $name", "$edge varc $varc  $previous -> $value", ability.structId.takeIf { seedsQueue })
    }

    fun pollBloodlust(stacks: Int, empowered: Boolean, cycle: Int) {
        if (stacks != lastStacks) {
            val delta = if (lastStacks == Int.MIN_VALUE) "initial" else "${lastStacks} -> $stacks"
            lastStacks = stacks
            record(cycle, "bloodlust stacks", delta)
        }
        if (empowered != lastEmpowered) {
            lastEmpowered = empowered
            record(cycle, "bloodlust empowered", empowered.toString())
        }
    }

    fun pollAdrenaline(adrenaline: Double, cycle: Int) {
        if (lastAdrenaline.isNaN()) {
            lastAdrenaline = adrenaline
            return
        }
        val delta = adrenaline - lastAdrenaline
        if (abs(delta) < ADRENALINE_EPSILON) return

        // Declared cost and observed spend disagree on ultimates, so log both against the cast that
        // caused them and let the real cost be derived rather than assumed.
        pendingCost?.let { pending ->
            pendingCost = null
            if (!pending.before.isNaN()) {
                val spent = pending.before - adrenaline
                ObservedCosts.record(pending.structId, pending.declared, spent)
                println(
                    "[BozoCap] COST %-20s declared=%.0f%% before=%.1f%% after=%.1f%% observed=%.1f%%"
                        .format(pending.name, pending.declared, pending.before, adrenaline, spent)
                )
            }
        }
        lastAdrenaline = adrenaline
        record(cycle, "adrenaline", "%+.1f%% -> %.1f%%".format(delta, adrenaline))
    }

    companion object {
        const val CYCLES_PER_TICK = CombatIds.CYCLES_PER_TICK
        private const val ADRENALINE_EPSILON = 0.05
    }
}
