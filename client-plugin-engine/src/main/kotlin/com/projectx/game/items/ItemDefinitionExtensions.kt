package com.projectx.game.items

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.ObjType

/**
 * Crafting/smithing material list from the item's params (2655+ material id, 2665+ amount, 2675+ enum
 * fallback). Game-coupled (returns engine [Item]s) so it stays engine-side rather than in :core.
 */
fun ObjType.getMaterials(): List<Item> {
    val mats = mutableListOf<Item>()
    try {
        for (i in 0 until 6) {
            var item: Item? = null
            if (getParamInt(2655 + i, -1) != -1) item = Item(getParamInt(2655 + i))
            val amount = getParamInt(2665 + i, -1)
            if (amount != -1 && item != null) {
                item.amount = amount
            } else if (amount != -1 && item == null) {
                val matId = Cache.enum(getParamInt(2675 + i, 0))?.values?.get(2655 + i) as? Int ?: continue
                item = Item(matId, amount)
            }
            item?.let { mats.add(it) }
        }
    } catch (_: Throwable) {
    }
    return mats
}
