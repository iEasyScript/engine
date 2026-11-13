package com.projectx.webwalker

import kotlin.test.Test
import kotlin.test.assertEquals

class JujuSpiritbagTest {

    @Test
    fun `buys two when there is room and money for them`() {
        assertEquals(2, JujuSpiritbag.buyCount(freeSlots = 10, coins = 50_000))
    }

    @Test
    fun `buys fewer when coins or backpack space run short`() {
        assertEquals(1, JujuSpiritbag.buyCount(freeSlots = 1, coins = 50_000))
        assertEquals(1, JujuSpiritbag.buyCount(freeSlots = 10, coins = 3_999))
        assertEquals(0, JujuSpiritbag.buyCount(freeSlots = 0, coins = 50_000))
        assertEquals(0, JujuSpiritbag.buyCount(freeSlots = 10, coins = 1_999))
    }
}
