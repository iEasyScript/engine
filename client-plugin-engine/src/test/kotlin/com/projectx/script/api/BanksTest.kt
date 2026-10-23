package com.projectx.script.api

import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BanksTest {

    @Test
    fun `orders banks nearest first`() {
        val seersMaples = Tile.of(2727, 3496, 0)

        val banks = Banks.byDistanceFrom(seersMaples)

        assertEquals("Seers' Village", banks.first().name)
        assertEquals(Banks.entries.size, banks.size)
    }

    @Test
    fun `lists each bank once`() {
        assertEquals(Banks.entries.size, Banks.entries.map { it.name }.toSet().size)
        assertTrue(Banks.entries.none { a -> Banks.entries.any { b -> a !== b && a.tile == b.tile } })
    }
}
