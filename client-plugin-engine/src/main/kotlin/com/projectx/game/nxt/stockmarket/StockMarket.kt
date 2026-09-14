package com.projectx.game.nxt.stockmarket

import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.OStockMarket
import com.projectx.game.nxt.OStockMarketOffer
import com.projectx.game.nxt.extent
import java.lang.foreign.MemorySegment

/**
 * The client's copy of the Grand Exchange offer slots, kept current by `UPDATE_STOCKMARKET_SLOT` whether or not
 * the exchange window is open. Records are grouped in blocks of [slotsPerGroup]; the normal exchange is
 * [DEFAULT_GROUP].
 */
class StockMarket(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OStockMarket.extent)

    val slotsPerGroup: Int
        get() = OStockMarket.SLOTS_PER_GROUP.toInt()

    @JvmOverloads
    fun offer(slot: Int, group: Int = DEFAULT_GROUP): StockMarketOffer {
        require(slot in 0 until slotsPerGroup) { "Grand Exchange slot $slot is outside 0..${slotsPerGroup - 1}" }
        val index = slot + group * slotsPerGroup
        val at = OStockMarket.OFFERS + index * OStockMarket.OFFER_STRIDE
        return StockMarketOffer(ptr.asSlice(at, OStockMarket.OFFER_STRIDE))
    }

    companion object {
        const val DEFAULT_GROUP = 0
    }
}

class StockMarketOffer(val ptr: MemorySegment) {
    val status: Int
        get() = ptr.readInt(OStockMarketOffer.STATUS)
    val type: Int
        get() = ptr.readInt(OStockMarketOffer.TYPE)
    val itemId: Int
        get() = ptr.readInt(OStockMarketOffer.ITEM_ID)
    val price: Long
        get() = ptr.readLong(OStockMarketOffer.PRICE)
    val quantity: Int
        get() = ptr.readInt(OStockMarketOffer.QUANTITY)
    val completedQuantity: Int
        get() = ptr.readInt(OStockMarketOffer.COMPLETED_QUANTITY)
    val completedGold: Long
        get() = ptr.readLong(OStockMarketOffer.COMPLETED_GOLD)
}
