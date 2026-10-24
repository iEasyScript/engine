package com.projectx.pathfinder

import com.projectx.pathfinder.GridWorld.Companion.tile
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HopModelTest {

    @Test
    fun `surge goes its full ten tiles on open ground`() {
        assertEquals(tile(3210, 3200), HopModel.surge(GridWorld().rule, tile(3200, 3200), Direction.EAST))
    }

    @Test
    fun `surge stops short of a wall`() {
        val world = GridWorld().block(3206, 3200)
        assertEquals(tile(3205, 3200), HopModel.surge(world.rule, tile(3200, 3200), Direction.EAST))
    }

    @Test
    fun `a diagonal hop slides round a building corner`() {
        val world = GridWorld().block(3201, 3201)
        val path = ArrayList<Tile>()
        assertEquals(tile(3203, 3203), HopModel.greedy(world.rule, tile(3200, 3200), tile(3203, 3203), path))
        assertEquals(tile(3201, 3200), path.first())
    }

    @Test
    fun `a dive within reach through a wall is refused`() {
        val world = GridWorld().blockRect(3205, 3195, 3205, 3205)
        assertNull(HopModel.dive(world.rule, tile(3200, 3200), tile(3208, 3200)))
    }

    @Test
    fun `a far dive snaps to eight ways and goes as far as it can`() {
        assertEquals(tile(3210, 3200), HopModel.dive(GridWorld().rule, tile(3200, 3200), tile(3220, 3207)))
    }

    @Test
    fun `eight-way directions favour the larger axis at the boundary`() {
        assertEquals(Direction.EAST, HopModel.dir8(20, 7))
        assertEquals(Direction.EAST, HopModel.dir8(20, 10))
        assertEquals(Direction.EAST, HopModel.dir8(2, 1))
        assertEquals(Direction.NORTH_EAST, HopModel.dir8(16, 9))
        assertEquals(Direction.SOUTH_WEST, HopModel.dir8(-5, -5))
        assertNull(HopModel.dir8(0, 0))
    }

    @Test
    fun `bearings snap to the nearest of the eight ways`() {
        assertEquals(Direction.NORTH, HopModel.fromBearing(10.0))
        assertEquals(Direction.EAST, HopModel.fromBearing(95.0))
        assertEquals(Direction.NORTH_WEST, HopModel.fromBearing(320.0))
        assertEquals(90.0, HopModel.bearing(Direction.EAST))
    }
}
