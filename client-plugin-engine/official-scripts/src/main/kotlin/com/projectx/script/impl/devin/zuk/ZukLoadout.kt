package com.projectx.script.impl.devin.zuk

import com.projectx.game.items.Item
import com.projectx.script.api.Equipment
import com.projectx.script.api.equipmentItem
import com.projectx.script.api.inventory

/**
 * The wave-15 shield swap.
 *
 * Barricade's duration scales with the tier of the shield it is used with, and the challenge minions
 * are **immune to damage** — so wearing a higher-tier shield for those few seconds costs nothing and
 * is the difference between covering three attacks and four.
 *
 * Both items are set explicitly in the config via the capture toggles (equip the item, tick the box)
 * which store a `Name#id` spec — matched by id when present, by name for hand-typed values.
 * Everything resolves *before* the rate gate is taken, so a missing item costs no input — the gate
 * is global, and a swap that could never land would otherwise starve the Barricade press of its one
 * input per tick.
 */
object ZukLoadout {

    /** Necromancy's lantern and a kiteshield occupy the same slot, so one accessor covers both. */
    private val OFFHAND = Equipment.Slot.SHIELD

    private var restorePending = false
    private val attempts = HashMap<String, Int>()

    /** The worn offhand as a `Name#id` spec, for the capture toggles. */
    fun wornSpec(): String? =
        runCatching { equipmentItem(OFFHAND) }.getOrNull()?.let { "${it.name}#${it.id}" }

    /**
     * True when the Barricade press may fire: the configured shield is worn, nothing is configured,
     * or the swap is genuinely impossible (shield absent, or its click attempts exhausted) — then
     * pressing on current gear beats eating the first attack unshielded.
     */
    fun challengeShieldReady(spec: String): Boolean {
        val wanted = parse(spec) ?: return true
        if (wornMatches(wanted)) return true
        if (inventoryItem(wanted) == null) return true
        return (attempts[SHIELD_KEY] ?: 0) >= MAX_ATTEMPTS
    }

    /** Returns true only when the shield is already on or a click was sent. */
    fun wearChallengeShield(spec: String): Boolean {
        val shield = parse(spec) ?: return false
        if (wornMatches(shield)) {
            restorePending = true
            attempts.remove(SHIELD_KEY)
            return true
        }
        val item = inventoryItem(shield) ?: return false
        if (!equip(item, SHIELD_KEY)) return false
        restorePending = true
        return true
    }

    /**
     * No-op unless a wave-15 swap is outstanding — the player's own offhand choices outside the
     * challenge are never fought. Clears once the configured offhand reads worn again.
     */
    fun restoreOffhand(spec: String): Boolean {
        if (!restorePending) return false
        val offhand = parse(spec) ?: return false
        if (wornMatches(offhand)) {
            restorePending = false
            attempts.remove(OFFHAND_KEY)
            return true
        }
        val item = inventoryItem(offhand) ?: return false
        return equip(item, OFFHAND_KEY)
    }

    private class Wanted(val name: String, val id: Int?)

    private fun parse(spec: String): Wanted? {
        val id = spec.substringAfterLast('#', "").toIntOrNull()
        val name = spec.substringBeforeLast('#').trim()
        if (name.isEmpty() && id == null) return null
        return Wanted(name, id)
    }

    private fun matches(item: Item, wanted: Wanted): Boolean =
        if (wanted.id != null) item.id == wanted.id else item.name.equals(wanted.name, ignoreCase = true)

    private fun wornMatches(wanted: Wanted): Boolean =
        runCatching { equipmentItem(OFFHAND) }.getOrNull()?.let { matches(it, wanted) } == true

    /** Drill verification: is the configured item actually worn right now? */
    fun wornMatchesSpec(spec: String): Boolean = parse(spec)?.let { wornMatches(it) } == true

    private fun inventoryItem(wanted: Wanted): Item? =
        runCatching { inventory.firstOrNull { matches(it, wanted) } }.getOrNull()

    private fun equip(item: Item, gateKey: String): Boolean {
        if ((attempts[gateKey] ?: 0) >= MAX_ATTEMPTS) return false
        val option = runCatching { equipOption(item) }.getOrNull() ?: return false
        if (!ZukInputGate.allow(gateKey, ZukInputGate.TICK_MS)) return false
        attempts[gateKey] = (attempts[gateKey] ?: 0) + 1
        return runCatching { item.click(option) }.getOrDefault(false)
    }

    fun reset() {
        restorePending = false
        attempts.clear()
    }

    /**
     * A swap that keeps failing must stop trying. The rate gate is global, so an equip that can never
     * land would take one input every tick forever and starve the Barricade press — losing the
     * challenge it exists to win. Cleared as soon as the relevant slot reads correct.
     */
    private const val MAX_ATTEMPTS = 3

    private const val SHIELD_KEY = "swap:shield"
    private const val OFFHAND_KEY = "swap:offhand"

    /**
     * The option is read off the item's own cache definition rather than assumed, so the click can
     * only ever carry an op the item actually declares.
     */
    private fun equipOption(item: Item): String? =
        item.invOps.filterNotNull().firstOrNull { op -> EQUIP_OPTIONS.any { it.equals(op, ignoreCase = true) } }

    private val EQUIP_OPTIONS = listOf("Wield", "Wear", "Equip")
}
