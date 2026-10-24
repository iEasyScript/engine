package com.projectx.script.api

import com.projectx.game.interfaces.Bank.Companion.doBankInventoryAction
import com.projectx.script.Script
import com.projectx.util.gaussian
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/** A bank a script can walk to: [tile] stands beside its booths, chest or bankers. */
class BankSite(val name: String, val tile: Tile) {
    override fun toString() = name
}

/** The banks [openNearestBank] walks to. */
object Banks {
    @JvmStatic
    val entries: List<BankSite> = listOf(
        BankSite("Seers' Village", Tile.of(2725, 3489, 0)),
        BankSite("Catherby", Tile.of(2796, 3440, 0)),
        BankSite("Ardougne south", Tile.of(2655, 3283, 0)),
        BankSite("Ardougne north", Tile.of(2616, 3332, 0)),
        BankSite("Yanille", Tile.of(2612, 3092, 0)),
        BankSite("Burthorpe", Tile.of(2888, 3536, 0)),
        BankSite("Taverley", Tile.of(2876, 3417, 0)),
        BankSite("Falador west", Tile.of(2946, 3368, 0)),
        BankSite("Falador east", Tile.of(3013, 3356, 0)),
        BankSite("Draynor Village", Tile.of(3092, 3245, 0)),
        BankSite("Edgeville", Tile.of(3094, 3493, 0)),
        BankSite("Grand Exchange", Tile.of(3164, 3484, 0)),
        BankSite("Varrock west", Tile.of(3185, 3436, 0)),
        BankSite("Varrock east", Tile.of(3253, 3420, 0)),
        BankSite("Lumbridge", Tile.of(3214, 3257, 0)),
        BankSite("Al Kharid", Tile.of(3270, 3168, 0)),
        BankSite("Canifis", Tile.of(3511, 3480, 0)),
        BankSite("Menaphos", Tile.of(3235, 2761, 0)),
    )

    /** The listed banks in order of straight-line distance from [from], nearest first. */
    @JvmStatic
    fun byDistanceFrom(from: Tile): List<BankSite> = entries.sortedBy { flatDistance(from, it.tile) }

    private fun flatDistance(a: Tile, b: Tile) = max(abs(a.x - b.x), abs(a.y - b.y))
}

private const val ROUTES_COMPARED = 3
private const val BANK_ARRIVE_DISTANCE = 4
private const val BANK_REACH = 15
private const val ACROSS_COUNTER = 4
private const val FIRST_BANK_SIDE_OP = 8

/** The params holding an item's own bank-side options, in the order the bank's backpack offers them. */
private val BANK_SIDE_OPTION_PARAMS = intArrayOf(1264, 1265)

/**
 * The bank in [Banks] the shortest walk away, comparing routes to the few nearest as the crow flies, or null when
 * none of them can be reached.
 */
suspend fun Script.findNearestBank(): BankSite? = Banks.byDistanceFrom(localPlayer.tile)
    .take(ROUTES_COMPARED)
    .mapNotNull { site -> findWebPath(site.tile, BANK_ARRIVE_DISTANCE).path?.let { site to it.size } }
    .minByOrNull { it.second }
    ?.first

/**
 * Opens a bank: the one in reach if there is one, otherwise the nearest in [Banks] after walking there. Types
 * [bankPin] when the bank asks for it; without one it waits for you to type it. Returns true once the bank is open.
 * Java: `Wait.openNearestBank`.
 */
suspend fun Script.openNearestBank(bankPin: String? = null): Boolean {
    if (bankOpen) return true
    if (openBankInReach(bankPin)) return true
    val site = findNearestBank() ?: return false
    val walk = webWalk(site.tile, BANK_ARRIVE_DISTANCE)
    // A walk that stops a few tiles short usually still has the bank in reach.
    if (openBankInReach(bankPin)) return true
    if (!walk.isSuccess) println("[Banks] Could not reach $site: $walk")
    return false
}

private suspend fun Script.openBankInReach(bankPin: String?): Boolean {
    // Bankers behind a counter, as at the Grand Exchange, can't be walked up to but are banked with across it.
    if (!openClosestBank(checkReachable = true, range = BANK_REACH) && !openClosestBank(range = ACROSS_COUNTER)) return false
    delayUntil(gaussian(6000L, 1_440_000L)) { bankOpen || BankPin.isOpen }
    if (BankPin.isOpen) {
        if (bankPin == null) delayUntil(gaussian(90_000L, 100_000_000L)) { !BankPin.isOpen }
        else if (!enterBankPin(bankPin)) return false
        delayUntil(gaussian(3000L, 490_000L)) { bankOpen }
    }
    return bankOpen
}

/**
 * Clicks one of [itemId]'s own bank-side options, such as an essence pouch's "Fill" or an ore box's "Empty - ore", in
 * the open bank's backpack. False when the bank isn't open, the item isn't in the backpack or it has no such option.
 */
fun bankInventoryOption(itemId: Int, option: String): Boolean {
    if (!bankOpen) return false
    val params = Cache.obj(itemId)?.params ?: return false
    val index = BANK_SIDE_OPTION_PARAMS.indexOfFirst { params[it] == option }
    return index >= 0 && doBankInventoryAction(itemId, FIRST_BANK_SIDE_OP + index)
}
