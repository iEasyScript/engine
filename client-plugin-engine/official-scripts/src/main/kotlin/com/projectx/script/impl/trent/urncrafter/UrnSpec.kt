package com.projectx.script.impl.trent.urncrafter

import world.gregs.voidps.cache.Cache
import com.projectx.game.items.Item
import com.projectx.script.api.inventory

private val IGNORE = RegexOption.IGNORE_CASE

private val URN_NAME = Regex("^(.+ urn)(?: \\((?:unfired|no rune|empty|full)\\))?$", IGNORE)

/** Stage suffixes preferred as the auto-detect source — finished urns persist, transient ones don't. */
private val DETECT_PRIORITY = listOf("(empty)", "(full)", "(no rune)", "(unfired)")

const val URN_SOFT_CLAY_COST = 2

private const val POTTERY_WHEEL_TABLES_ENUM = 7004
private const val POTTERY_WHEEL_NAMES_ENUM = 7005

fun urnFamilyOf(name: String): String? = URN_NAME.matchEntire(name)?.groupValues?.get(1)

/**
 * Maps each urn family to the pottery-wheel MakeX category that lists it, read from the cache.
 * The wheel groups urns by skill ("Mining Urns", …) except the three combat urns, which live under
 * "Prayer Urns" — a split that can't be derived from the family name, so we resolve it from the data.
 */
private val potteryWheelCategoryByFamily: Map<String, String> by lazy {
    val tables = Cache.enum(POTTERY_WHEEL_TABLES_ENUM)?.map ?: return@lazy emptyMap()
    val names = Cache.enum(POTTERY_WHEEL_NAMES_ENUM)?.map ?: return@lazy emptyMap()
    buildMap {
        for ((index, productEnumId) in tables) {
            val category = names[index] as? String ?: continue
            val products = Cache.enum(productEnumId as? Int ?: continue)?.map?.values ?: continue
            for (productId in products) {
                val urnName = Cache.obj(productId as? Int ?: continue)?.name ?: continue
                urnFamilyOf(urnName)?.let { putIfAbsent(it, category) }
            }
        }
    }
}

fun potteryWheelCategory(family: String): String? = potteryWheelCategoryByFamily[family]

fun unfiredRegex(family: String) = Regex("${Regex.escape(family)} \\(unfired\\)", IGNORE)
fun noRuneRegex(family: String) = Regex("${Regex.escape(family)} \\(no rune\\)", IGNORE)
fun emptyRegex(family: String) = Regex("${Regex.escape(family)} \\(empty\\)", IGNORE)

fun detectUrnFamily(): String? {
    val urns = inventory.filter { urnFamilyOf(it.name) != null }
    if (urns.isEmpty()) return null
    val best = urns.minByOrNull { item ->
        DETECT_PRIORITY.indexOfFirst { item.name.endsWith(it) }.let { if (it == -1) DETECT_PRIORITY.size else it }
    } ?: return null
    return urnFamilyOf(best.name)
}

fun addRuneAction(noRune: Item): String? = noRune.invOps.firstOrNull { it?.startsWith("Add ", true) == true }

fun runeNameFromAction(action: String): String =
    action.removePrefix("Add ").removePrefix("add ").trim().replaceFirstChar { it.uppercase() }
