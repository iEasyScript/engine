package com.projectx.webwalker

import com.projectx.game.interfaces.IFSlot
import com.projectx.pathfinder.navpath.NavProfile
import com.projectx.script.Script
import com.projectx.script.api.coinPouch
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.util.FeatureIds
import com.projectx.util.gaussian
import world.gregs.voidps.gameval.Gameval

/**
 * Buys juju teleport spiritbags from Papa Mambo when a route's spiritbag teleport used the last one: he stands by the
 * Herblore Habitat landing, so the next route through there still has one. Up to [RESTOCK_COUNT], fewer when coins or
 * free backpack slots run short; never in combat, and never while the master farmer hat is held, whose teleport spends
 * banked bags rather than these.
 */
internal object JujuSpiritbag {
    private const val RESTOCK_COUNT = 2
    private const val PRICE = 2_000
    private const val MAMBO_RANGE = 10
    private const val LANDING_TIMEOUT_MS = 10_000L
    private const val SHOP_OPEN_TIMEOUT_MS = 5_000L
    // The shop shows open a moment before its stock grid fills.
    private const val STOCK_TIMEOUT_MS = 1_800L
    private const val BUY_TIMEOUT_MS = 3_000L
    private const val CLOSE_TIMEOUT_MS = 1_500L

    private val ids = FeatureIds("JujuSpiritbag") {
        Ids(
            bag = Gameval.requireId(Gameval.OBJ, "ecosystem_teleport_bag"),
            coins = Gameval.requireId(Gameval.OBJ, "coins"),
            mambo = Gameval.requireId(Gameval.NPC, "ecosystem_papa_mambo"),
            stock = slot("shop_main:list_background"),
            icons = slot("shop_main:list_icon"),
            buy = slot("shop_main:buy_selected"),
            close = slot("shop_main:mainmodal_window_close_button"),
        )
    }

    private class Ids(val bag: Int, val coins: Int, val mambo: Int, val stock: IFSlot, val icons: IFSlot, val buy: IFSlot, val close: IFSlot)

    private fun slot(name: String): IFSlot = Gameval.requireComponentHash(name).let { IFSlot(it ushr 16, it and 0xFFFF) }

    /** Backpack spiritbags before a link, or -1 when [link] is not a spiritbag teleport. */
    fun before(link: WebLink): Int {
        val bag = ids.get()?.bag ?: return -1
        return if (link.kind == WebLinkKind.ITEM && link.target?.id == bag) backpackBags(bag) else -1
    }

    /** After a spiritbag teleport that began with [bagsBefore] bags: restocks when that was the last and it landed. */
    suspend fun afterTeleport(script: Script, bagsBefore: Int) {
        if (bagsBefore != 1) return
        val known = ids.get() ?: return
        script.delayUntil(LANDING_TIMEOUT_MS) { atLanding() && backpackBags(known.bag) == 0 }
        if (!atLanding() || backpackBags(known.bag) != 0) return
        restock(script, known)
    }

    private suspend fun restock(script: Script, known: Ids) {
        when {
            NavProfile.inCombat() -> return println("[WebWalk] Used the last spiritbag; in combat, so not buying more")
            MasterFarmerHat.held() -> return println("[WebWalk] Used the last spiritbag; the master farmer hat runs on banked ones, so not buying more")
        }
        val want = buyCount(inventory.freeSlots, coins(known.coins))
        if (want == 0) return println("[WebWalk] Used the last spiritbag; not enough coins or backpack space to buy more")
        val mambo = findClosestNPC(MAMBO_RANGE) { it.id == known.mambo } ?: return println("[WebWalk] Used the last spiritbag; Papa Mambo is not nearby")
        println("[WebWalk] Used the last spiritbag; buying $want from Papa Mambo")
        if (!interfaces.isOpen(known.stock.interfaceId)) {
            if (!mambo.interact("Trade")) return
            script.delayUntil(SHOP_OPEN_TIMEOUT_MS) { interfaces.isOpen(known.stock.interfaceId) }
            if (!interfaces.isOpen(known.stock.interfaceId)) return println("[WebWalk] Papa Mambo's shop did not open")
        }
        script.delayUntil(STOCK_TIMEOUT_MS) { stockSlot(known) >= 0 }
        val slot = stockSlot(known)
        if (slot < 0) {
            println("[WebWalk] Papa Mambo has no spiritbags in stock")
        } else {
            for (i in 0 until want) {
                val held = backpackBags(known.bag)
                if (!IFSlot(known.stock.interfaceId, known.stock.componentId, slot).click(1)) break
                script.delay(gaussian(220, 3_600))
                if (!known.buy.click(1)) break
                script.delayUntil(BUY_TIMEOUT_MS) { backpackBags(known.bag) > held }
                if (backpackBags(known.bag) <= held) break
            }
            println("[WebWalk] Restocked: ${backpackBags(known.bag)} spiritbags in the backpack")
        }
        if (interfaces.isOpen(known.close.interfaceId) && known.close.click(1)) {
            script.delayUntil(CLOSE_TIMEOUT_MS) { !interfaces.isOpen(known.close.interfaceId) }
        }
    }

    /** Bags to buy: [RESTOCK_COUNT], fewer when free slots (they do not stack) or coins run short. */
    fun buyCount(freeSlots: Int, coins: Long): Int {
        if (freeSlots <= 0 || coins < PRICE) return 0
        return minOf(RESTOCK_COUNT.toLong(), freeSlots.toLong(), coins / PRICE).toInt()
    }

    private fun stockSlot(known: Ids): Int =
        interfaces.getComponentRaw(known.icons.interfaceId, known.icons.componentId)?.slotChildren
            ?.firstOrNull { it.itemId == known.bag }?.slotId ?: -1

    private fun coins(coins: Int): Long =
        (runCatching { coinPouch.firstOrNull()?.amount ?: 0 }.getOrDefault(0) + inventory.filter { it.id == coins }.sumOf { it.amount }).toLong()

    private fun backpackBags(bag: Int): Int = inventory.filter { it.id == bag }.sumOf { it.amount }

    private fun atLanding(): Boolean = localPlayer.tile.let { MasterFarmerHat.inLandingBox(it.x, it.y, it.plane) }
}
