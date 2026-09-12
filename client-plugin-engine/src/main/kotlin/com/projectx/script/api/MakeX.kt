package com.projectx.script.api

import world.gregs.voidps.cache.Cache
import com.projectx.game.cs2.CS2Executor
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.util.hashFromInterface

private const val MAKE_COUNTER_BUILD_SCRIPT = 10451

/**
 * Materialise the per-value "Select" entries on a counter/slider overlay. They are otherwise created
 * lazily only on the first real slider interaction, so a freshly built slider has none and
 * [setMakeCounter] would no-op. Call this before [setMakeCounter] on a fresh slider.
 */
fun buildMakeCounterEntries(interfaceId: Int, select: Int, count: Int) {
    CS2Executor.executeScript(
        MAKE_COUNTER_BUILD_SCRIPT,
        -1, hashFromInterface(interfaceId, select), 0, 0, 1, count.coerceAtLeast(1), 0,
    )
}

/**
 * Set a counter/slider widget to [target] by firing the per-value Select op the widget builds on
 * [select] (child index `target-min`) — the only interaction that commits the value server-side.
 */
fun setMakeCounter(interfaceId: Int, container: Int, select: Int, min: Int, max: Int, target: Int): Boolean {
    if (interfaces.getComponent(interfaceId, container) == null) return false
    if (target < min || target > max) return false
    val entries = interfaces.getComponent(interfaceId, select)?.slotChildren?.size ?: 0
    if (target - min >= entries) return false
    return IFSlot(interfaceId, select, target - min).click(1)
}

object MakeX {
    const val PARENT = 1370
    const val PANEL = 1371
    const val MAKE_BUTTON = 30
    const val GRID = 22
    const val CATEGORY_TOGGLE = 28
    const val CATEGORY_BUILD = 27

    private const val SELECTED_ITEM_VARP = 1170
    private const val MAX_QUANTITY_VARP = 8846
    const val SUBCATEGORY_VARP = 1169
    const val CATEGORY_NAMES_ENUM_VARP = 7881

    // Overlay that hosts the built category dropdown, keyed by the active top-level (game/lobby/login).
    val CATEGORY_OVERLAYS = listOf(CatList(1477, 896), CatList(906, 165), CatList(744, 356))

    val isOpen get() = interfaces.isOpen(PARENT)
    val hasPanel get() = interfaces.isOpen(PANEL)
    val inProgress get() = hasActiveMakeXProgress
    val selectedItemId get() = varps.getVar(SELECTED_ITEM_VARP)
    val maxQuantity get() = varps.getVar(MAX_QUANTITY_VARP)

    fun craftables(): List<Craftable> {
        val grid = interfaces.getComponent(PANEL, GRID) ?: return emptyList()
        return grid.slotChildren
            .filter { it.itemId > 0 }
            .map { Craftable(it.itemId, Cache.obj(it.itemId)?.name ?: "null", it.slotId - 1) }
    }

    fun activeCategoryOverlay(): CatList? = CATEGORY_OVERLAYS.firstOrNull { interfaces.isOpen(it.iface) }
}

data class Craftable(val itemId: Int, val name: String, val selectSlot: Int)
data class CatList(val iface: Int, val selectComp: Int)

/** Always applied (even when antiban is off) so dependent clicks are never fired back-to-back. */
suspend fun Script.makeXReaction(mean: Int = 820, variance: Int = 520) = delay(mean, variance)

suspend fun Script.makeXConfirm(): Boolean {
    if (!MakeX.isOpen) return false
    makeXReaction(720, 460)
    continueMakeX()
    delayUntil(6000) { MakeX.inProgress || !MakeX.isOpen }
    return true
}

/**
 * Switches the MakeX category (recipe list). The dropdown row that [match] names is chosen by its
 * index in the live category-name enum, which is also the wire slot of the selection.
 *
 * The dropdown is opened by a client-side script, so a synthesized click alone would send the
 * server the open request without ever rendering the rows — leaving nothing safe to click. We send
 * the open request (server context) and drive the render ourselves, then fire the row's server op.
 */
suspend fun Script.selectMakeCategory(match: (String) -> Boolean): Boolean {
    val namesEnumId = varps.getVar(MakeX.CATEGORY_NAMES_ENUM_VARP)
    val names = Cache.enum(namesEnumId)?.map ?: return false
    val index = names.entries.firstOrNull { (_, name) -> (name as? String)?.let(match) == true }?.key ?: return false
    val overlay = MakeX.activeCategoryOverlay() ?: return false
    val maxIndex = names.keys.max()

    repeat(3) {
        val before = varps.getVar(MakeX.SUBCATEGORY_VARP)
        renderMakeCategoryDropdown(namesEnumId, maxIndex)
        delayUntil(2000) { interfaces.getComponent(overlay.iface, overlay.selectComp)?.visible == true }
        if (interfaces.getComponent(overlay.iface, overlay.selectComp)?.visible == true) {
            IFSlot(overlay.iface, overlay.selectComp, index).click(1)
            delayUntil(2500) { varps.getVar(MakeX.SUBCATEGORY_VARP) != before }
            if (varps.getVar(MakeX.SUBCATEGORY_VARP) != before) return true
        }
    }
    return false
}

private suspend fun Script.renderMakeCategoryDropdown(namesEnumId: Int, maxIndex: Int) {
    val toggleHash = hashFromInterface(MakeX.PANEL, MakeX.CATEGORY_TOGGLE)
    IFSlot(MakeX.PANEL, MakeX.CATEGORY_TOGGLE, -1).click(1)
    delay(260, 110)
    CS2Executor.executeScript(MAKE_DROPDOWN_CLOSE_SCRIPT)
    delay(130, 60)
    CS2Executor.executeScript(
        MAKE_DROPDOWN_OPEN_SCRIPT,
        toggleHash, -1, hashFromInterface(MakeX.PANEL, MakeX.CATEGORY_BUILD),
        MAKE_DROPDOWN_RENDER_SUBID, namesEnumId, -1, maxIndex, 0, 0, 0,
    )
    delay(200, 80)
}

private const val MAKE_DROPDOWN_OPEN_SCRIPT = 10434
private const val MAKE_DROPDOWN_CLOSE_SCRIPT = 10444
private const val MAKE_DROPDOWN_RENDER_SUBID = 3

fun componentSlotExists(iface: Int, comp: Int, slot: Int): Boolean =
    interfaces.getComponent(iface, comp)?.slotChildren?.any { it.slotId == slot } == true

suspend fun Script.makeXSelect(itemMatch: (String) -> Boolean, categoryMatch: ((String) -> Boolean)? = null): Boolean {
    if (!MakeX.isOpen) return false
    if (selectCraftable(itemMatch)) return true
    if (categoryMatch != null && selectMakeCategory(categoryMatch)) {
        delayUntil(3000) { MakeX.craftables().any { itemMatch(it.name) } }
        if (selectCraftable(itemMatch)) return true
    }
    return false
}

suspend fun Script.makeX(itemMatch: (String) -> Boolean, categoryMatch: ((String) -> Boolean)? = null): Boolean {
    if (!makeXSelect(itemMatch, categoryMatch)) return false
    return makeXConfirm()
}

private suspend fun Script.selectCraftable(match: (String) -> Boolean): Boolean {
    val target = MakeX.craftables().firstOrNull { match(it.name) } ?: return false
    if (MakeX.selectedItemId == target.itemId) return true
    if (!componentSlotExists(MakeX.PANEL, MakeX.GRID, target.selectSlot)) return false
    makeXReaction(980, 620)
    if (!componentSlotExists(MakeX.PANEL, MakeX.GRID, target.selectSlot)) return false
    IFSlot(MakeX.PANEL, MakeX.GRID, target.selectSlot).click(1)
    delayUntil(2500) { MakeX.selectedItemId == target.itemId }
    return MakeX.selectedItemId == target.itemId
}
