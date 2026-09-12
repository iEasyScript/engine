package com.projectx.script.impl.trent.aiosmithing

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.StructType

// Smithable-item params (mirrors jag clientscript2542's upgrade walk).
private const val PARAM_TIER = 7804       // this item's upgrade tier (absent on a base item -> 0)
private const val PARAM_DOWNGRADE = 7806  // the item one tier below this one (-1 on a base)
private const val PARAM_UPGRADE = 7807    // the item one tier above this one (-1 at the top)
private const val PARAM_IS_BURIAL = 7803  // 1 on the burial variant

/** Burial is selected as tier 50 in the forge (varbitplayer_43239 / TIER_BUTTON). */
const val BURIAL_TIER = 50

fun itemTier(itemId: Int): Int = Cache.obj(itemId)?.params?.get(PARAM_TIER) as? Int ?: 0
fun itemUpgrade(itemId: Int): Int = Cache.obj(itemId)?.params?.get(PARAM_UPGRADE) as? Int ?: -1
fun itemIsBurial(itemId: Int): Boolean = (Cache.obj(itemId)?.params?.get(PARAM_IS_BURIAL) as? Int ?: 0) == 1

/** The forge TIER_BUTTON value that produces [itemId] (its numeric tier, or [BURIAL_TIER]). */
fun makeTierOf(itemId: Int): Int = if (itemIsBurial(itemId)) BURIAL_TIER else itemTier(itemId)

/** Walk down to the tier-0 base of [itemId]'s family. */
fun familyBase(itemId: Int): Int {
    var id = itemId
    repeat(16) {
        val down = Cache.obj(id)?.params?.get(PARAM_DOWNGRADE) as? Int ?: -1
        if (down <= 0 || down == id) return id
        id = down
    }
    return id
}

/**
 * Item ids for the family rooted at [base], from the base up to [targetTier] (50 = Burial). Index 0 is
 * the base; the last entry is the target. Walks the per-item upgrade param so it works for any family.
 */
fun familyChain(base: Int, targetTier: Int): List<Int> {
    val chain = mutableListOf(base)
    var id = base
    while (chain.size < 16) {
        val reached = if (targetTier == BURIAL_TIER) itemIsBurial(id) else itemTier(id) >= targetTier
        if (reached) break
        val up = itemUpgrade(id)
        if (up <= 0) break
        chain.add(up)
        id = up
    }
    return chain
}

// Bar economics from the cache recipes. Each tier item points at a recipe struct via item-param 2675;
// that struct lists material pairs (item 2655+i / qty 2665+i) and names the metal bar in param 7763.
// The bar cost for a step is the qty paired with that bar (Rune gauntlets: base 1, +1 1, +2 2, +3 4 -> 8).
private const val RECIPE_STRUCT_PARAM = 2675
private const val STRUCT_BAR_ITEM_PARAM = 7763
private const val STRUCT_MATERIAL_ITEM_BASE = 2655
private const val STRUCT_MATERIAL_QTY_BASE = 2665

private fun recipeStruct(item: Int): StructType? =
    (Cache.obj(item)?.params?.get(RECIPE_STRUCT_PARAM) as? Int ?: -1).takeIf { it > 0 }?.let { Cache.struct(it) }

/** Metal bar the step producing [item] consumes (e.g. Rune bar 2363), or -1 (e.g. the burial step). */
fun stepBarItem(item: Int): Int = recipeStruct(item)?.getIntValue(STRUCT_BAR_ITEM_PARAM, -1) ?: -1

/** Bars the step producing [item] consumes — the qty paired with the bar in its recipe struct; 0 if none. */
fun stepBarCost(item: Int): Int {
    val struct = recipeStruct(item) ?: return 0
    val bar = struct.getIntValue(STRUCT_BAR_ITEM_PARAM, -1)
    if (bar < 0) return 0
    for (i in 0 until 6) {
        if (struct.getIntValue(STRUCT_MATERIAL_ITEM_BASE + i, -1) == bar) {
            return struct.getIntValue(STRUCT_MATERIAL_QTY_BASE + i, 0)
        }
    }
    return 0
}

/** The metal bar consumed across [chain] (first step that costs bars), or -1. */
fun chainBarItem(chain: List<Int>): Int =
    chain.firstNotNullOfOrNull { stepBarItem(it).takeIf { id -> id > 0 } } ?: -1

/** Total bars to take one item from raw bars to the top of [chain] (8 for Rune gauntlets +3). */
fun chainCumulativeBars(chain: List<Int>): Int = chain.sumOf { stepBarCost(it) }
