package com.projectx.script.impl.trent.dungeoneering

import com.projectx.script.api.inventory
import world.gregs.voidps.cache.Cache

/**
 * What counts as food on a floor is not a fixed id list — every floor's kills and Smuggler stock roll a
 * different tier of fish — so it is read off the cache: an item is food when its type exposes an Eat op.
 * Puzzle items that look edible do not: the bait-the-plate Vile fish only exposes Drop, and a pack full of
 * those is exactly what let a boss kill the bot four times with the heal path silently unreachable.
 */
fun edible(itemId: Int): Boolean = (Cache.obj(itemId)?.getInvOpIdForName("Eat") ?: -1) != -1

fun carriedFood(): Int = inventory.filter { edible(it.id) }.sumOf { it.amount }

/**
 * The Smuggler sells NO cooked food — its six fish lines are all raw, and raw fish exposes only Drop, so it
 * counts for nothing until a fire turns it into food. Matched on the cache name because the cooked item
 * points back at its raw form (param 2655) but the raw item carries no forward link to cook toward.
 */
fun rawFood(itemId: Int): Boolean =
    !edible(itemId) && Cache.obj(itemId)?.name?.startsWith("Raw ") == true

fun carriedRawFood(): Int = inventory.filter { rawFood(it.id) }.sumOf { it.amount }
