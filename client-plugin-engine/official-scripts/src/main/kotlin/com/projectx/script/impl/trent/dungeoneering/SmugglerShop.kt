package com.projectx.script.impl.trent.dungeoneering

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.varps
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import com.projectx.util.gaussian
import world.gregs.voidps.type.Tile

/**
 * Supply run at the Smuggler. Three separate things depend on it: food for every fight (the pack starts a
 * floor empty and scavenging alone does not keep up with a boss), and dungeoneering feathers for the puzzles
 * that cannot be solved without them (pondskater's fishing keys, bait-the-plate's lure).
 *
 * It engages only while the player is in the Smuggler's OWN room. Daemonheim rooms are sealed by doors and
 * diagonal neighbours share no passage at all, so a plain tile radius sees a Smuggler eight tiles away that
 * the avatar cannot walk to under any circumstances - which is exactly how a run burned twenty walks into a
 * wall and then wrote the floor's supplies off.
 *
 * Because one silent failure takes all three down at once, nothing here fails quietly, and a shop that does
 * not open is retried rather than written off on the first miss.
 */
object SmugglerShop {
    private const val SMUGGLER = 11226
    private const val FEATHER = 17796
    private const val RUSTY_COINS = 18201  // the dungeon shop currency - earned in-floor from kills/skilling
    private const val SHOP = 956
    private const val SHOP_CLOSE = 9       // dungeon_shop:mainmodal_window_close_button
    private const val STOCK_COMPONENT = 3  // dungeon_shop:click - the clickable buy layer (captured real buy)
    private const val ITEM_COMPONENT = 2   // display layer holding the per-slot item ids we rank for the ordinal
    private const val BUY_OPTION = 6       // captured: OpNum 6 IFSlot(956, 956:3, 53)
    private const val TARGET_FEATHERS = 15
    private const val TARGET_FOOD = 12
    private const val REACH_ATTEMPTS = 8
    private const val OPEN_ATTEMPTS = 3
    private const val BUY_CLICKS = 20
    private const val RANGE = 24
    private const val ROOM_TILES = 16

    // The remaster reduced dungeon complexity to two values (Low / High); feather puzzles only roll on High.
    // The exact player varbit + its High value still need pinning against the live client (2256
    // rand_previous_dungeon_complexity read 0 in a High floor, so it's the wrong one); until then this stays
    // permissive and the per-item held/afford gates keep it from over-buying.
    private const val COMPLEXITY_VARBIT = -1
    private const val COMPLEXITY_HIGH = 1

    private var reachAttempts = 0
    private var openAttempts = 0

    private fun roomOf(tile: Tile): Pair<Int, Int> = tile.x / ROOM_TILES to tile.y / ROOM_TILES

    private fun smuggler(): NPC? {
        val room = roomOf(localPlayer.tile)
        return allNpcsWithinRange(RANGE) { it.id == SMUGGLER && roomOf(it.tile) == room }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) }
    }

    fun smugglerInRoom(): Boolean = smuggler() != null

    private fun highComplexity(): Boolean {
        if (COMPLEXITY_VARBIT < 0) return true
        return varps.getVarBit(COMPLEXITY_VARBIT) == COMPLEXITY_HIGH
    }

    // Feathers are topped up only from empty - some perks start the player with a stack and we must not waste
    // coins on it - while food is bought up to target because it is consumed all floor. Food is worth having
    // on any floor; feathers only matter where the puzzles needing them can roll.
    private fun needsFeathers(): Boolean = highComplexity() && inventory.count(FEATHER) == 0

    fun needsSupplies(): Boolean =
        inventory.count(RUSTY_COINS) > 0 &&
            (carriedFood() + carriedRawFood() < TARGET_FOOD || needsFeathers())

    /**
     * True once the run is DONE for this floor - bought, or given up on for a reason worth logging. False
     * while still walking to the Smuggler or retrying the open, so the caller comes back next loop instead of
     * abandoning the whole run after one slow step.
     */
    suspend fun stockUp(script: Script): Boolean {
        // Walking out of the Smuggler's room mid-run is not a failure - the floor is still explorable and the
        // bot passes back through - so leave the run pending rather than writing the supplies off.
        val smuggler = smuggler() ?: return false

        if (!interfaces.isOpen(SHOP)) {
            if (smuggler.tile.getDistance(localPlayer.tile) > 2) {
                if (++reachAttempts > REACH_ATTEMPTS) {
                    reachAttempts = 0
                    println("DUNG-SHOP: cannot reach the Smuggler inside its own room - NO food and NO feathers this floor")
                    return true
                }
                walkTo(smuggler.tile, false)
                script.waitUntilNotMoving()
                return false
            }
            reachAttempts = 0
            if (smuggler.interact("Trade"))
                script.delayUntil(gaussian(8000L, 1500L)) { interfaces.isOpen(SHOP) }
            if (!interfaces.isOpen(SHOP)) {
                if (++openAttempts < OPEN_ATTEMPTS) {
                    println("DUNG-SHOP: 'Trade' did not open shop $SHOP ($openAttempts/$OPEN_ATTEMPTS) - retrying")
                    script.delay(1600, 420)
                    return false
                }
                openAttempts = 0
                println("DUNG-SHOP: 'Trade' never opened shop $SHOP - NO food and NO feathers this floor")
                return true
            }
            openAttempts = 0
            // Let the stock grid populate before reading/clicking it - touching a half-initialised interface
            // vector right after open is a native crash.
            script.delay(1200, 320)
        }

        // Raw, because that is the only food the Smuggler carries; the bot cooks it at the first fire it
        // passes. First match wins, and the stock is tier-ordered, so this takes the lowest Cooking level.
        buy(script, "raw fish", TARGET_FOOD, ::rawFood)
        if (needsFeathers()) buy(script, "dungeoneering feathers", TARGET_FEATHERS) { it == FEATHER }
        IFSlot(SHOP, SHOP_CLOSE, -1).click(1)
        return true
    }

    /**
     * The buy click indexes stock by the item's ORDINAL position (captured: 956:3 slot 53), NOT the raw
     * interface child-slot. Rank the real stock items on the display layer (skipping the 0xFFFFFF price/label
     * sub-slots) by child-slot; an item's index there is the ordinal the click layer uses.
     */
    private fun stockOrdinal(want: (Int) -> Boolean): Int {
        val grid = interfaces.getComponent(SHOP, ITEM_COMPONENT) ?: return -1
        return grid.slotChildren
            .filter { it.itemId in 1..0xFFFFFE }
            .sortedBy { it.slotId }
            .indexOfFirst { want(it.itemId) }
    }

    // Stack-aware: feathers arrive as one slot holding many, food as one slot each.
    private fun carried(want: (Int) -> Boolean): Int = inventory.filter { want(it.id) }.sumOf { it.amount }

    private suspend fun buy(script: Script, label: String, target: Int, want: (Int) -> Boolean) {
        if (carried(want) >= target) return
        val ordinal = stockOrdinal(want)
        if (ordinal < 0) {
            println("DUNG-SHOP: no $label in the Smuggler's stock - none this floor")
            return
        }
        val coins = inventory.count(RUSTY_COINS)
        if (coins < target) println("DUNG-SHOP: only $coins rusty coins for $label - buying what they cover")

        val start = carried(want)
        var clicks = 0
        while (carried(want) < target && clicks++ < BUY_CLICKS) {
            val before = carried(want)
            IFSlot(SHOP, STOCK_COMPONENT, ordinal).click(BUY_OPTION)
            script.delayUntil(gaussian(2500L, 600L)) { carried(want) > before }
            if (carried(want) <= before) break
        }
        val bought = carried(want) - start
        if (bought > 0) println("DUNG-SHOP: bought $bought $label (now ${carried(want)}) via stock slot $ordinal")
        else println("DUNG-SHOP: could not buy any $label from stock slot $ordinal")
    }
}
