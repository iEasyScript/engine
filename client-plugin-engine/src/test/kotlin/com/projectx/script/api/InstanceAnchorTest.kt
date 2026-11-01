package com.projectx.script.api

import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceAnchorTest {
    private val anchor = InstanceAnchor(6500, 7000, 1)

    @Test
    fun `offsets convert to world tiles and back`() {
        assertEquals(Tile(6492, 6986, 1), anchor.tile(-8, -14))
        assertEquals(6509, anchor.worldX(9))
        assertEquals(6979, anchor.worldY(-21))
        assertEquals(9, anchor.offsetX(anchor.worldX(9)))
        assertEquals(-21, anchor.offsetY(anchor.worldY(-21)))
    }

    @Test
    fun `a tile anchor matches the coordinate anchor`() {
        assertEquals(anchor, InstanceAnchor(Tile(6500, 7000, 1)))
        assertEquals(Tile(6500, 7000, 1), anchor.tile)
    }

    @Test
    fun `the rectangle includes its edges and nothing past them`() {
        assertTrue(anchor.contains(6485, 6965, -15, -35, 15, -4))
        assertTrue(anchor.contains(6515, 6996, -15, -35, 15, -4))
        assertFalse(anchor.contains(6484, 6980, -15, -35, 15, -4))
        assertFalse(anchor.contains(6500, 6997, -15, -35, 15, -4))
        assertFalse(anchor.contains(6500, 7000, -15, -35, 15, -4))
    }

    @Test
    fun `corners may be given in either order`() {
        assertTrue(anchor.contains(6510, 6990, 15, -4, -15, -35))
    }

    @Test
    fun `a tile on another plane is outside`() {
        assertTrue(anchor.contains(Tile(6500, 6990, 1), -15, -35, 15, -4))
        assertFalse(anchor.contains(Tile(6500, 6990, 0), -15, -35, 15, -4))
    }
}
