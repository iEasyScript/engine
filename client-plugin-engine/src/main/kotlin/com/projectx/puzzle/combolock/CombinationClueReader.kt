package com.projectx.puzzle.combolock

import kotlin.math.atan2

/** A ground item as the clue reader sees it: display name, stack amount, and tile coordinates. */
data class ClueItem(val name: String, val amount: Int, val tileX: Int, val tileY: Int)

/**
 * Derives the 4-letter chest combination from the ground items in a Desperate Times puzzle room.
 *
 * Returns null when the answer can't be read from ground items alone — recipe notes and lodestone
 * plaques are handled separately. [anchorX]/[anchorY] is the chest (or player) tile, used to order
 * items by distance and angle as the rules require.
 *
 * Pure and engine-independent so it can be unit-tested without the live cache.
 */
object CombinationClueReader {

    private val HERB_KEYWORDS = listOf(
        "guam", "marrentill", "tarromin", "harralander", "ranarr", "toadflax", "irit", "avantoe",
        "kwuarm", "snapdragon", "cadantine", "lantadyme", "dwarf weed", "torstol", "spirit weed",
        "wergali", "bloodweed", "fellstalk", "arbuck", "goutweed",
    )

    private val ROMAN = mapOf(1 to 'I', 5 to 'V', 10 to 'X', 50 to 'L', 100 to 'C', 500 to 'D', 1000 to 'M')

    fun derive(items: List<ClueItem>, anchorX: Int, anchorY: Int): String? {
        if (items.isEmpty()) return null

        // Coin piles: each pile's amount is a single Roman numeral, read clockwise around the chest.
        if (items.all { isCoins(it.name) }) {
            if (items.size != 4) return null
            val ordered = items.sortedBy { clockwiseAngle(it.tileX - anchorX, it.tileY - anchorY) }
            return ordered.map { ROMAN[it.amount] ?: return null }.joinToString("")
        }

        // A single clean herb on the ground → the literal word HERB.
        if (items.size == 1 && isCleanHerb(items[0].name)) return "HERB"

        // A single item with a four-letter word in its name (Bronze dart → DART, Beer → BEER).
        if (items.size == 1) return fourLetterWord(items[0].name)?.uppercase()

        // Four items → first letter of each, ordered farthest-from-chest to closest.
        if (items.size == 4) {
            val ordered = items.sortedByDescending { dist2(it.tileX - anchorX, it.tileY - anchorY) }
            return ordered.map { it.name.trim().firstOrNull()?.uppercaseChar() ?: return null }.joinToString("")
        }

        return null
    }

    private fun isCoins(name: String) = name.equals("Coins", ignoreCase = true)

    private fun isCleanHerb(name: String): Boolean {
        val n = name.lowercase()
        if (n.contains("grimy")) return false
        return HERB_KEYWORDS.any { n.contains(it) }
    }

    /** A standalone four-letter word in the name, else the last four letters of a single long word. */
    private fun fourLetterWord(name: String): String? {
        val words = name.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
        words.firstOrNull { it.length == 4 }?.let { return it }
        val single = words.singleOrNull() ?: return null
        return if (single.length > 4) single.takeLast(4) else null
    }

    private fun dist2(dx: Int, dy: Int) = dx * dx + dy * dy

    /** Clockwise angle from north (+y): N≈0, E≈π/2, S≈π, W≈3π/2, so ascending order is clockwise. */
    private fun clockwiseAngle(dx: Int, dy: Int): Double {
        val a = atan2(dx.toDouble(), dy.toDouble())
        return if (a < 0) a + 2 * Math.PI else a
    }
}
