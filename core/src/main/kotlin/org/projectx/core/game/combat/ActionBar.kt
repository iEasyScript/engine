package org.projectx.core.game.combat

import org.projectx.core.model.Vars

data class RawSlot(val type: Int, val id: Int, val obj: Int) {
    /** An uninitialised data bar reads obj `0`; only obj `> 0` is a real item, so `obj <= 0` with no type is empty. */
    val empty: Boolean get() = type == 0 && obj <= 0

    companion object {
        val EMPTY = RawSlot(0, 0, ActionBar.EMPTY_OBJ)
    }
}

/**
 * Shared read/write API for the RS3 action-bar var model, usable from both the server (mutating a
 * player's [Vars]) and the engine (reading a live client). A data bar (1..18, plus [TEMP_BAR]) holds
 * 14 slots, each a `(type, id, obj)` triple: an ability is `(styleType, enumKey, -1)`; an item is
 * `(0, 0, itemId)` — the client renders any slot with `obj != -1` as that item, so item slots need
 * only the obj varp set. The on-screen bar windows (main + up to four extras) each display whichever
 * data bar their `current_bar*` varbit selects.
 */
object ActionBar {
    const val MAX_DATA_BAR = 18
    const val TEMP_BAR = ActionBarModel.TEMP_BAR
    const val SLOTS = ActionBarModel.SLOTS
    const val EMPTY_OBJ = ActionBarModel.OBJ_EMPTY

    /** On-screen extra bar windows are indexed 2..5 ([CombatIds.ADDITIONAL_BARS] holds their selector varbits). */
    val EXTRA_BAR_LOCS = 2..5

    fun readRaw(v: VarReader, bar: Int, slot: Int): RawSlot {
        val refs = ActionBarModel.slotVarRefs[bar to slot] ?: return RawSlot.EMPTY
        return RawSlot(v.getVarBit(refs.typeVarbit), v.getVarBit(refs.idVarbit), v.getVar(refs.objVar))
    }

    fun binding(v: VarReader, bar: Int, slot: Int): SlotBinding {
        val raw = readRaw(v, bar, slot)
        if (raw.obj > 0) return ItemSlot(raw.obj, raw.type, raw.id)
        if (raw.type == 0) return EmptySlot
        val struct = ActionBarModel.refForStruct.entries.firstOrNull { it.value.type == raw.type && it.value.id == raw.id }?.key
        return if (struct != null) AbilitySlot(struct) else EmptySlot
    }

    fun hasContent(v: VarReader, bar: Int): Boolean =
        (1..SLOTS).any { !readRaw(v, bar, it).empty }

    fun writeRaw(vars: Vars, bar: Int, slot: Int, raw: RawSlot, save: Boolean = true, forceSend: Boolean = false) {
        val refs = ActionBarModel.slotVarRefs[bar to slot] ?: return
        vars.setVarBit(refs.typeVarbit, raw.type, save = save, forceSend = forceSend)
        vars.setVarBit(refs.idVarbit, raw.id, save = save, forceSend = forceSend)
        vars.setVar(refs.objVar, raw.obj, save = save, forceSend = forceSend)
    }

    fun write(vars: Vars, bar: Int, slot: Int, binding: SlotBinding, save: Boolean = true) {
        val raw = when (binding) {
            is AbilitySlot -> ActionBarModel.refForStruct[binding.structId]?.let { RawSlot(it.type, it.id, EMPTY_OBJ) } ?: return
            is ItemSlot -> RawSlot(binding.type, binding.id, binding.itemId)
            EmptySlot -> RawSlot.EMPTY
        }
        writeRaw(vars, bar, slot, raw, save)
    }

    fun setAbility(vars: Vars, bar: Int, slot: Int, structId: Int, save: Boolean = true) =
        write(vars, bar, slot, AbilitySlot(structId), save)

    fun setItem(vars: Vars, bar: Int, slot: Int, itemId: Int, save: Boolean = true) =
        write(vars, bar, slot, ItemSlot(itemId), save)

    fun clearSlot(vars: Vars, bar: Int, slot: Int, save: Boolean = true, forceSend: Boolean = false) =
        writeRaw(vars, bar, slot, RawSlot.EMPTY, save, forceSend)

    /** Moves the source slot onto the target, swapping the two triples (target content lands back on the source). */
    fun move(vars: Vars, fromBar: Int, fromSlot: Int, toBar: Int, toSlot: Int, save: Boolean = true) {
        if (fromBar == toBar && fromSlot == toSlot) return
        val from = readRaw(vars, fromBar, fromSlot)
        val to = readRaw(vars, toBar, toSlot)
        writeRaw(vars, toBar, toSlot, from, save)
        writeRaw(vars, fromBar, fromSlot, to, save)
    }

    /** Drops a binding onto a slot without touching any source (ability book / inventory → bar). */
    fun place(vars: Vars, bar: Int, slot: Int, binding: SlotBinding, save: Boolean = true) =
        write(vars, bar, slot, binding, save)

    fun clearBar(vars: Vars, bar: Int, save: Boolean = true, forceSend: Boolean = false) {
        for (slot in 1..SLOTS) clearSlot(vars, bar, slot, save, forceSend)
    }

    // --- extra on-screen bars ---

    fun extraBarSelectorVar(barLoc: Int): Int? =
        CombatIds.ADDITIONAL_BARS.getOrNull(barLoc - 2)

    /** The data bar an extra window defaults to — 5..8, kept clear of data bars 1..4 (the main bar's per-style variants). */
    fun defaultDataBar(barLoc: Int): Int = barLoc + 3

    fun isExtraEnabled(v: VarReader, barLoc: Int): Boolean {
        val selector = extraBarSelectorVar(barLoc) ?: return false
        return v.getVarBit(selector) > 0
    }

    /** barLoc (2..5) -> the data bar it currently displays, for every enabled extra window. */
    fun enabledExtraBars(v: VarReader): List<Pair<Int, Int>> =
        EXTRA_BAR_LOCS.mapNotNull { loc ->
            val selector = extraBarSelectorVar(loc) ?: return@mapNotNull null
            val dataBar = v.getVarBit(selector)
            if (dataBar > 0) loc to dataBar else null
        }

    fun enableExtraBar(vars: Vars, barLoc: Int, dataBar: Int = defaultDataBar(barLoc)) {
        val selector = extraBarSelectorVar(barLoc) ?: return
        vars.setVarBit(selector, dataBar, save = true)
        pushBar(vars, dataBar)
    }

    fun disableExtraBar(vars: Vars, barLoc: Int) {
        val selector = extraBarSelectorVar(barLoc) ?: return
        vars.setVarBit(selector, 0, save = true, forceSend = true)
    }

    /**
     * Force-sends every slot of [dataBar] to the client, normalising uninitialised slots (`obj <= 0`, no
     * type) to a clean empty `obj = -1` while preserving real ability/item bindings — so both empty and
     * populated bars render correctly.
     */
    fun pushBar(vars: Vars, dataBar: Int) {
        for (slot in 1..SLOTS) {
            val raw = readRaw(vars, dataBar, slot)
            writeRaw(vars, dataBar, slot, if (raw.empty) RawSlot.EMPTY else raw, save = false, forceSend = true)
        }
    }

    /**
     * Configures and populates every on-screen action bar (main + any enabled extras) from the player's
     * persisted selector setting: re-sends each `current_bar*` varbit and pushes its data bar's slots.
     * Extras whose selector is 0 are left closed — a no-op when the player has no extra bars enabled.
     */
    fun applyOnScreenBars(vars: Vars) {
        pushOnScreenBar(vars, CombatIds.CURRENT_BAR)
        for (barLoc in EXTRA_BAR_LOCS) extraBarSelectorVar(barLoc)?.let { pushOnScreenBar(vars, it) }
    }

    private fun pushOnScreenBar(vars: Vars, selectorVar: Int) {
        val dataBar = vars.getVarBit(selectorVar)
        if (dataBar <= 0) return
        vars.setVarBit(selectorVar, dataBar, save = false, forceSend = true)
        pushBar(vars, dataBar)
    }

    /**
     * Points the mobile on-screen bars (buttons + revolution) at the main current data bar. The mobile
     * bar renders from its own [CombatIds.MOBILE_BARS] selectors, and a drop onto a mobile bar is
     * rejected unless its selector names a real data bar — so this must run for mobile logins.
     */
    fun applyMobileBars(vars: Vars) {
        val main = vars.getVarBit(CombatIds.CURRENT_BAR)
        if (main <= 0) return
        for (selector in CombatIds.MOBILE_BARS) vars.setVarBit(selector, main, save = true, forceSend = true)
    }

    // --- ability queueing ---

    /** Slot types that place an ability on the global cooldown and can therefore be queued (combat styles). */
    val QUEUEABLE_ABILITY_TYPES = setOf(1, 3, 4, 5, 6, 11, 17)

    fun isQueueableType(type: Int): Boolean = type in QUEUEABLE_ABILITY_TYPES

    /** `combatv2_queuing_off` is inverted — 0 means the player has ability queueing enabled. */
    fun queuingEnabled(v: VarReader): Boolean = v.getVarBit(CombatIds.QUEUING_OFF) == 0

    /** Visually queues the ability in [dataBar]/[slot] on the client's action bar (queue animation). */
    fun queueAbility(vars: Vars, dataBar: Int, slot: Int) {
        vars.setVar(CombatIds.QUEUED_BAR, dataBar, forceSend = true)
        vars.setVar(CombatIds.QUEUED_SLOT, slot, forceSend = true)
    }

    fun clearQueue(vars: Vars) {
        vars.setVar(CombatIds.QUEUED_BAR, 0, forceSend = true)
        vars.setVar(CombatIds.QUEUED_SLOT, 0, forceSend = true)
    }
}
