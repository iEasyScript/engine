package com.projectx.script.api

import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AreaTest {

    @Test
    fun `overlaps a tile inside and not one outside`() {
        val area = Area.Rectangular(Tile.of(3200, 3200, 0), Tile.of(3205, 3205, 0))

        assertTrue(area.overlaps(Tile.of(3203, 3201, 0)))
        assertFalse(area.overlaps(Tile.of(3210, 3201, 0)))
        assertFalse(area.overlaps(Tile.of(3203, 3201, 1)))
        assertTrue(area.overlaps(Tile.of(3203, 3201, 1), ignoreZ = true))
    }

    @Test
    fun `coordinate constructors match the tile ones`() {
        val rectangle = Area.Rectangular(3205, 3205, 3200, 3200, 0)
        val circle = Area.Circular(3200, 3200, 0, 3.0)
        val triangle = Area.Polygonal(intArrayOf(3200, 3210, 3200), intArrayOf(3200, 3200, 3210), 0)

        assertTrue(rectangle.contains(3200, 3205, 0))
        assertFalse(rectangle.contains(3206, 3205, 0))
        assertTrue(circle.contains(3202, 3200, 0))
        assertFalse(circle.contains(3204, 3200, 0))
        assertTrue(triangle.contains(3202, 3202, 0))
        assertFalse(triangle.contains(3209, 3209, 0))
        assertEquals(3200, circle.getCenterX())
    }

    @Test
    fun `the centre tile is the average of the area's tiles`() {
        val centre = Area.Rectangular(3200, 3200, 3204, 3204, 0).centreTile()

        assertEquals(Tile.of(3202, 3202, 0), centre)
    }
}
