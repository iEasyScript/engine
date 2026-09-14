package com.projectx.script.api

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.input.Key
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.items.Item
import com.projectx.game.nxt.OffsetUnavailableException
import com.projectx.script.Script
import world.gregs.voidps.cache.Cache

enum class GrandExchangeOfferStatus(val id: Int) {
    EMPTY(0),
    /** Confirmed locally, waiting for the server to accept it. */
    ADDING(1),
    ACTIVE(2),
    /** Traded out, waiting for the server to finish it; seen for a moment before [FINISHED]. */
    COMPLETING(3),
    /** Abort requested, waiting for the server to settle it. */
    ABORTING(4),
    /** Nothing left to trade: either every item was traded or the offer was aborted. */
    FINISHED(5),
    UNKNOWN(-1);

    companion object {
        @JvmStatic
        fun of(id: Int): GrandExchangeOfferStatus = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

enum class GrandExchangeOfferType { BUY, SELL }

data class GrandExchangeOffer(
    val slot: Int,
    val status: GrandExchangeOfferStatus,
    val type: GrandExchangeOfferType,
    val itemId: Int,
    val price: Long,
    val quantity: Int,
    val completedQuantity: Int,
    val completedGold: Long,
) {
    val isEmpty: Boolean get() = status == GrandExchangeOfferStatus.EMPTY
    val isActive: Boolean
        get() = status == GrandExchangeOfferStatus.ADDING || status == GrandExchangeOfferStatus.ACTIVE ||
            status == GrandExchangeOfferStatus.COMPLETING
    val isFinished: Boolean get() = status == GrandExchangeOfferStatus.FINISHED
    val isCompleted: Boolean get() = isFinished && completedQuantity >= quantity
    val isAborted: Boolean get() = isFinished && completedQuantity < quantity
    val remainingQuantity: Int get() = (quantity - completedQuantity).coerceAtLeast(0)
    val itemName: String get() = if (itemId > 0) Cache.obj(itemId)?.name ?: "" else ""
}

/**
 * The Grand Exchange. Offers are read from the client's own copy of the exchange state, so they stay current
 * with the window closed. Buying, selling, aborting and collecting need the window open ([geOpen]).
 *
 * Offer slots are numbered 0 to [SLOT_COUNT] - 1, left to right and top to bottom.
 */
object GrandExchange {
    const val INTERFACE = 105
    const val COLLECT_ALL_INTERFACE = 651
    const val SLOT_COUNT = 8

    private const val FIRST_SLOT_BOX = 12
    private const val SLOT_BOX_STRIDE = 14
    private const val BUY_BUTTON_OFFSET = 4
    private const val SELL_BUTTON_OFFSET = 7
    internal const val ABORT_OPTION = 2

    internal const val SEARCH_BAR = 225
    internal const val SEARCH_RESULTS = 229
    internal const val QUANTITY_BOX = 170
    internal const val PRICE_BOX = 185
    internal const val CONFIRM_BUTTON = 212
    internal const val COLLECT_TO_INVENTORY = 0

    internal const val OFFER_ITEM_VARP = 135
    internal const val OFFER_QUANTITY_VARP = 136
    internal const val OFFER_PRICE_VARP = 137
    internal const val OFFER_SLOT_VARP = 138
    internal const val OFFER_TYPE_VARP = 139
    internal const val OFFER_MARKET_PRICE_VARP = 140

    internal const val SELL_SIDE_INTERFACE = 107
    internal const val SELL_SIDE_BACKPACK = 7

    private val COLLECTION_INVENTORIES = intArrayOf(523, 524, 525, 526, 527, 528, 783, 784)

    @JvmStatic
    val isOpen: Boolean
        get() = runCatching { interfaces.isOpen(INTERFACE) }.getOrDefault(false)

    /** True while the buy or sell setup screen is showing rather than the eight offer boxes. */
    @JvmStatic
    val isSettingUpOffer: Boolean
        get() = isOpen && varps.getVar(OFFER_SLOT_VARP) >= 0

    /** The slot the setup screen is editing, or -1. */
    @JvmStatic
    val setupSlot: Int
        get() = if (isOpen) varps.getVar(OFFER_SLOT_VARP) else -1

    /** The item chosen on the setup screen, or -1. */
    @JvmStatic
    val setupItemId: Int
        get() = varps.getVar(OFFER_ITEM_VARP)

    @JvmStatic
    val setupQuantity: Int
        get() = varps.getVar(OFFER_QUANTITY_VARP)

    @JvmStatic
    val setupPrice: Long
        get() = varps.getVarLong(OFFER_PRICE_VARP)

    /** The exchange's guide price for the item on the setup screen. */
    @JvmStatic
    val setupMarketPrice: Long
        get() = varps.getVarLong(OFFER_MARKET_PRICE_VARP)

    /**
     * False on a client build whose exchange state has not been reverse engineered yet; every offer read then
     * reports empty slots.
     */
    @JvmStatic
    val isSupported: Boolean
        get() = try {
            Bootstrap.client.stockMarket
            true
        } catch (_: OffsetUnavailableException) {
            false
        }

    @JvmStatic
    fun offer(slot: Int): GrandExchangeOffer {
        require(slot in 0 until SLOT_COUNT) { "Grand Exchange slot $slot is outside 0..${SLOT_COUNT - 1}" }
        val raw = try {
            Bootstrap.client.stockMarket.offer(slot)
        } catch (_: OffsetUnavailableException) {
            return emptyOffer(slot)
        }
        return GrandExchangeOffer(
            slot = slot,
            status = GrandExchangeOfferStatus.of(raw.status),
            type = if (raw.type == 0) GrandExchangeOfferType.BUY else GrandExchangeOfferType.SELL,
            itemId = raw.itemId,
            price = raw.price,
            quantity = raw.quantity,
            completedQuantity = raw.completedQuantity,
            completedGold = raw.completedGold,
        )
    }

    @JvmStatic
    fun offers(): List<GrandExchangeOffer> = (0 until SLOT_COUNT).map(::offer)

    @JvmStatic
    fun activeOffers(): List<GrandExchangeOffer> = offers().filter { !it.isEmpty }

    /** The first empty slot, or -1. A free account's locked slots also read as empty; buying into one fails. */
    @JvmStatic
    fun firstEmptySlot(): Int = offers().firstOrNull { it.isEmpty }?.slot ?: -1

    /** What is waiting to be collected from [slot]: the items bought, or the coins from a sale or refund. */
    @JvmStatic
    fun collectable(slot: Int): List<Item> {
        require(slot in 0 until SLOT_COUNT) { "Grand Exchange slot $slot is outside 0..${SLOT_COUNT - 1}" }
        return runCatching {
            Bootstrap.client.inventoryManager.getWithInterface(COLLECTION_INVENTORIES[slot], -1, -1).filter { it.amount > 0 }
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    fun hasCollectable(): Boolean = (0 until SLOT_COUNT).any { collectable(it).isNotEmpty() }

    /** [itemId] and its banknote (or, for a banknote, the item it stands for): the exchange trades either form as one item. */
    @JvmStatic
    fun itemForms(itemId: Int): IntArray {
        val linked = Cache.obj(itemId)?.notedItemId ?: -1
        return if (linked > 0) intArrayOf(itemId, linked) else intArrayOf(itemId)
    }

    internal fun slotBox(slot: Int) = FIRST_SLOT_BOX + slot * SLOT_BOX_STRIDE
    internal fun buyButton(slot: Int) = slotBox(slot) + BUY_BUTTON_OFFSET
    internal fun sellButton(slot: Int) = slotBox(slot) + SELL_BUTTON_OFFSET

    /**
     * The item names the search list is showing (including its recent-search and favourite rows), by the slot a
     * click on that row uses. Rows carry only the name, so items that share a name cannot be told apart here.
     */
    @JvmStatic
    fun searchResults(): Map<Int, String> {
        val rows = searchRows()
        val slots = rows.map { it.slotId }.toSet()
        return rows
            .filter { row -> row.text.any { it.isLetterOrDigit() } && row.text.all { it.code in 32..126 } && !row.text.endsWith(":") }
            .associate { row -> clickableRowSlot(row.slotId, slots) to row.text }
    }

    /** A row is a clickable container followed by its name text; the name itself takes no clicks. */
    private fun clickableRowSlot(nameSlot: Int, slots: Set<Int>) = if (nameSlot - 1 in slots) nameSlot - 1 else nameSlot

    internal fun searchResultSlot(itemId: Int): Int? {
        val name = Cache.obj(itemId)?.name ?: return null
        return searchResults().entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key
    }

    internal fun searchRows() =
        interfaces.getComponent(INTERFACE, SEARCH_RESULTS)?.slotChildren.orEmpty().filter { it.slotId >= 0 }

    private fun emptyOffer(slot: Int) = GrandExchangeOffer(
        slot, GrandExchangeOfferStatus.EMPTY, GrandExchangeOfferType.BUY, -1, 0L, 0, 0, 0L,
    )
}

/** Opens the exchange through the nearest clerk or banker offering "Exchange". */
suspend fun Script.geOpen(timeoutMillis: Long = 8000): Boolean {
    if (GrandExchange.isOpen) return true
    val clerk = findClosestNPCWithOption(EXCHANGE_OPTION) ?: return false
    if (!clerk.interact(EXCHANGE_OPTION)) return false
    delayUntil(timeoutMillis) { GrandExchange.isOpen }
    return GrandExchange.isOpen
}

/**
 * Places a buy offer for [quantity] of [itemId] at [price] coins each in [slot] (default: the first empty one).
 * Returns true once the server has accepted the offer. The exchange window must be open.
 */
suspend fun Script.geBuy(itemId: Int, quantity: Int, price: Long, slot: Int = GrandExchange.firstEmptySlot()): Boolean {
    if (!canPlaceOffer(slot, quantity, price)) return false
    if (!openSetup(GrandExchange.buyButton(slot), slot, GrandExchangeOfferType.BUY)) return false
    if (!chooseSearchResult(itemId)) return false
    return finishOffer(slot, quantity, price)
}

/**
 * Places a sell offer for [quantity] of [itemId] from the backpack, noted or not, at [price] coins each in [slot]
 * (default: the first empty one). Returns true once the server has accepted the offer. The exchange window must be
 * open.
 */
suspend fun Script.geSell(itemId: Int, quantity: Int, price: Long, slot: Int = GrandExchange.firstEmptySlot()): Boolean {
    if (!canPlaceOffer(slot, quantity, price)) return false
    val forms = GrandExchange.itemForms(itemId)
    if (inventory.count(*forms) < quantity) return false
    if (!openSetup(GrandExchange.sellButton(slot), slot, GrandExchangeOfferType.SELL)) return false
    val backpackSlot = inventory.firstOrNull { it.id in forms && it.amount > 0 }?.slot?.slotId ?: return false
    geReaction()
    IFSlot(GrandExchange.SELL_SIDE_INTERFACE, GrandExchange.SELL_SIDE_BACKPACK, backpackSlot).click(1)
    delayUntil(4000) { GrandExchange.setupItemId in forms }
    if (GrandExchange.setupItemId !in forms) return false
    return finishOffer(slot, quantity, price)
}

/** Aborts the offer in [slot]. Returns true once the server has settled it; collect the refund with [geCollectAll]. */
suspend fun Script.geAbort(slot: Int): Boolean {
    if (!GrandExchange.isOpen || GrandExchange.isSettingUpOffer) return false
    if (!GrandExchange.offer(slot).isActive) return false
    geReaction()
    IFSlot(GrandExchange.INTERFACE, GrandExchange.slotBox(slot), -1).click(GrandExchange.ABORT_OPTION)
    delayUntil(8000) { GrandExchange.offer(slot).let { it.isFinished || it.isEmpty } }
    return GrandExchange.offer(slot).let { it.isFinished || it.isEmpty }
}

/** Collects everything waiting in every slot into the backpack (coins go to the money pouch). */
suspend fun Script.geCollectAll(): Boolean {
    if (!GrandExchange.isOpen || !GrandExchange.hasCollectable()) return false
    geReaction()
    if (!IFSlot(GrandExchange.COLLECT_ALL_INTERFACE, GrandExchange.COLLECT_TO_INVENTORY, -1).click(1)) return false
    delayUntil(6000) { !GrandExchange.hasCollectable() }
    return !GrandExchange.hasCollectable()
}

/** The pause a player takes before each exchange click. Always applied, so dependent clicks never fire back-to-back. */
suspend fun Script.geReaction(mean: Int = 760, variance: Int = 480) = delay(mean, variance)

private const val EXCHANGE_OPTION = "Exchange"
private const val SEARCH_QUERY_LIMIT = 24

private fun canPlaceOffer(slot: Int, quantity: Int, price: Long): Boolean =
    GrandExchange.isOpen && (!GrandExchange.isSettingUpOffer || GrandExchange.setupSlot == slot) &&
        slot in 0 until GrandExchange.SLOT_COUNT && GrandExchange.offer(slot).isEmpty &&
        quantity > 0 && price > 0

/** Picks up a setup screen a previous attempt left open for the same slot and type instead of failing on it. */
private suspend fun Script.openSetup(button: Int, slot: Int, type: GrandExchangeOfferType): Boolean {
    if (GrandExchange.setupSlot == slot) return varps.getVar(GrandExchange.OFFER_TYPE_VARP) == type.ordinal
    geReaction()
    if (!IFSlot(GrandExchange.INTERFACE, button, -1).click(1)) return false
    delayUntil(4000) { GrandExchange.setupSlot == slot }
    return GrandExchange.setupSlot == slot && varps.getVar(GrandExchange.OFFER_TYPE_VARP) == type.ordinal
}

private suspend fun Script.chooseSearchResult(itemId: Int): Boolean {
    if (GrandExchange.setupItemId == itemId) return true
    val name = Cache.obj(itemId)?.name ?: return false
    val query = name.lowercase().takeWhile { canTypeText(it.toString()) }.take(SEARCH_QUERY_LIMIT).trimEnd()
    if (query.isEmpty()) return false
    geReaction()
    if (!IFSlot(GrandExchange.INTERFACE, GrandExchange.SEARCH_BAR, -1).click(1)) return false
    delay(520, 260)
    typeText(query)
    delayUntil(4000) { GrandExchange.searchResultSlot(itemId) != null }
    val resultSlot = GrandExchange.searchResultSlot(itemId) ?: return false
    geReaction(640, 360)
    IFSlot(GrandExchange.INTERFACE, GrandExchange.SEARCH_RESULTS, resultSlot).click(1)
    delayUntil(4000) { GrandExchange.setupItemId == itemId }
    return GrandExchange.setupItemId == itemId
}

/**
 * Both boxes are always entered: choosing an item makes the server reset them (price to the guide price, a sell's
 * quantity to everything carried) a moment later, so a value that already looks right can still be overwritten.
 */
private suspend fun Script.finishOffer(slot: Int, quantity: Int, price: Long): Boolean {
    if (!enterAmount(GrandExchange.QUANTITY_BOX, quantity.toString()) { GrandExchange.setupQuantity == quantity }) return false
    if (!enterAmount(GrandExchange.PRICE_BOX, price.toString()) { GrandExchange.setupPrice == price }) return false
    if (GrandExchange.setupQuantity != quantity || GrandExchange.setupPrice != price) return false
    geReaction()
    if (!IFSlot(GrandExchange.INTERFACE, GrandExchange.CONFIRM_BUTTON, -1).click(1)) return false
    delayUntil(8000) { GrandExchange.offer(slot).let { !it.isEmpty && it.status != GrandExchangeOfferStatus.ADDING } }
    return !GrandExchange.offer(slot).isEmpty
}

private suspend fun Script.enterAmount(box: Int, digits: String, landed: () -> Boolean): Boolean {
    repeat(2) {
        geReaction()
        if (!IFSlot(GrandExchange.INTERFACE, box, -1).click(1)) return false
        delay(560, 280)
        typeText(digits)
        delay(180, 90)
        pressKey(Key.RETURN)
        delayUntil(3000, predicate = landed)
        if (landed()) return true
    }
    return false
}
