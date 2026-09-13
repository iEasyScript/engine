package com.projectx.script.api

import world.gregs.voidps.cache.Cache
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script

object Smithing {
    const val INTERFACE = 37
    const val ITEM_NAME = 40
    const val MAKE_BUTTON = 163
    const val ITEM_LIST = 32
    const val QUANTITY_COUNTER = 34
    const val QUANTITY_SELECT = 35
    const val QUANTITY_MIN = 1

    const val BASE_OBJECT_VARP = 8333
    const val MATERIAL_DBROW_VARP = 8332
    const val METAL_CATEGORY_VARP = 8331
    const val TIER_VARBIT = 43239
    const val QUANTITY_VARP = 8336

    const val METAL_BANK_INV = 858
    @JvmStatic
    val metalBank get() = Bootstrap.client.inventoryManager[METAL_BANK_INV]

    @JvmStatic
    val TIER_BUTTON = linkedMapOf(0 to 149, 1 to 161, 2 to 159, 3 to 157, 4 to 155, 5 to 153, 50 to 151)

    @JvmStatic
    val isOpen get() = interfaces.isOpen(INTERFACE)
    @JvmStatic
    val baseObjectId get() = varps.getVar(BASE_OBJECT_VARP)
    @JvmStatic
    val tier get() = varps.getVarBit(TIER_VARBIT)
    @JvmStatic
    val quantity get() = varps.getVar(QUANTITY_VARP)
    @JvmStatic
    val selectedName get() = interfaces.getComponent(INTERFACE, ITEM_NAME)?.text ?: ""

    @JvmStatic
    fun tierAvailable(t: Int) =
        TIER_BUTTON[t]?.let { interfaces.getComponent(INTERFACE, it) != null } == true

    @JvmStatic
    fun listRows() = interfaces.getComponent(INTERFACE, ITEM_LIST)?.slotChildren.orEmpty()

    @JvmStatic
    val quantityMax get() = (interfaces.getComponent(INTERFACE, QUANTITY_SELECT)?.slotChildren?.size ?: 0) + QUANTITY_MIN - 1
}

/**
 * Retries because selecting a tier rebuilds and resets the slider. Returns the committed quantity
 * (>=1 once it lands).
 */
suspend fun Script.smithSetQuantity(target: Int): Int {
    if (!Smithing.isOpen) return 0
    val clamped = target.coerceAtLeast(Smithing.QUANTITY_MIN)
    if (Smithing.quantity == clamped) return clamped
    makeXReaction()
    repeat(4) {
        if (!Smithing.isOpen) return 0
        buildMakeCounterEntries(Smithing.INTERFACE, Smithing.QUANTITY_SELECT, clamped)
        delay(650, 200)
        setMakeCounter(Smithing.INTERFACE, Smithing.QUANTITY_COUNTER, Smithing.QUANTITY_SELECT, Smithing.QUANTITY_MIN, clamped, clamped)
        delayUntil(1500) { Smithing.quantity == clamped }
        if (Smithing.quantity == clamped) return clamped
        delay(400, 150)
    }
    return Smithing.quantity
}

const val UNFINISHED_SMITHING_ITEM = 47068

suspend fun Script.smithSelectTier(target: Int): Boolean {
    if (!Smithing.isOpen) return false
    if (Smithing.tier == target) return true
    val comp = Smithing.TIER_BUTTON[target] ?: return false
    if (interfaces.getComponent(Smithing.INTERFACE, comp) == null) return false
    makeXReaction()
    IFSlot(Smithing.INTERFACE, comp, -1).click(1)
    delayUntil(2500) { Smithing.tier == target }
    return Smithing.tier == target
}

suspend fun Script.smithSelectItem(match: (String) -> Boolean): Boolean {
    if (!Smithing.isOpen) return false
    if (match(Smithing.selectedName)) return true
    val row = Smithing.listRows().firstOrNull { it.text.isNotBlank() && match(it.text) } ?: return false
    val before = Smithing.baseObjectId
    makeXReaction()
    IFSlot(Smithing.INTERFACE, Smithing.ITEM_LIST, row.slotId).click(1)
    delayUntil(2500) { Smithing.baseObjectId != before }
    return match(Smithing.selectedName)
}

suspend fun Script.smithMake(): Boolean {
    if (!Smithing.isOpen) return false
    val before = inventory.count(UNFINISHED_SMITHING_ITEM)
    makeXReaction(700, 450)
    IFSlot(Smithing.INTERFACE, Smithing.MAKE_BUTTON, -1).click(1)
    delayUntil(6000) { inventory.count(UNFINISHED_SMITHING_ITEM) > before || !Smithing.isOpen }
    return inventory.count(UNFINISHED_SMITHING_ITEM) > before
}

fun smithSelectedDisplay(): String {
    val base = Smithing.baseObjectId
    val name = if (base > 0) Cache.obj(base)?.name ?: "?" else Smithing.selectedName
    val suffix = when (val t = Smithing.tier) {
        0 -> ""; 50 -> " (Burial)"; else -> " +$t"
    }
    return "$name$suffix"
}

fun smithBarsAvailable(barItem: Int): Int {
    if (barItem <= 0) return 0
    val manager = Bootstrap.client.inventoryManager
    return if (manager.exists(Smithing.METAL_BANK_INV)) manager[Smithing.METAL_BANK_INV].count(barItem) else 0
}

@JvmOverloads
fun smithBatchSize(barItem: Int, cumulativeBars: Int, cap: Int = 20): Int {
    if (cumulativeBars <= 0) return 0
    return minOf(cap, smithBarsAvailable(barItem) / cumulativeBars)
}
