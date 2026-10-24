package com.projectx.pathfinder

import com.projectx.pathfinder.GridWorld.Companion.tile
import world.gregs.voidps.type.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalWalkingTest {

    @Test
    fun `walking distance goes round a wall rather than through it`() {
        val world = GridWorld().blockRect(3205, 3195, 3205, 3205)
        val map = world.map()
        assertTrue(map.distance(tile(3200, 3200), tile(3210, 3200), 50) in 12..14)
        assertTrue(map.distance(tile(3204, 3200), tile(3206, 3200), 50) > 2)
        assertEquals(WalkMap.UNREACHABLE, map.distance(tile(3204, 3200), tile(3206, 3200), 3))
    }

    @Test
    fun `a field reports unreachable tiles and tiles outside its window apart`() {
        val world = GridWorld().blockRect(3209, 3190, 3211, 3210)
            .blockRect(3190, 3190, 3230, 3190).blockRect(3190, 3210, 3230, 3210)
            .blockRect(3190, 3190, 3190, 3210).blockRect(3230, 3190, 3230, 3210)
        val field = world.map().field(tile(3200, 3200), 40)!!
        assertEquals(WalkMap.UNREACHABLE, field.distanceTo(tile(3220, 3200)))
        assertEquals(WalkMap.UNKNOWN, field.distanceTo(tile(3300, 3200)))
        assertEquals(3, field.distanceTo(tile(3203, 3201)))
    }

    @Test
    fun `the server walk tries west east south north before diagonals`() {
        val path = GridWorld().map().serverPath(tile(3200, 3200), tile(3202, 3201))!!
        assertEquals(listOf(tile(3200, 3200), tile(3201, 3200), tile(3202, 3201)), path)
    }

    @Test
    fun `an unreachable click walks to the nearest reached tile`() {
        val world = GridWorld().blockRect(3204, 3200, 3206, 3202)
        val path = world.map().serverPath(tile(3200, 3201), tile(3205, 3201))!!
        assertEquals(tile(3203, 3201), path.last())
    }

    @Test
    fun `an open door joins its two sides and a shut one does not`() {
        val shut = GridWorld().blockRect(3200, 3190, 3200, 3210).map()
        assertEquals(false, shut.passage(tile(3199, 3200), tile(3201, 3200), 3))
        val open = GridWorld().blockRect(3200, 3190, 3200, 3199).blockRect(3200, 3201, 3200, 3210).map()
        assertEquals(true, open.passage(tile(3199, 3200), tile(3201, 3200), 3))
    }

    @Test
    fun `an untrusted map answers unknown`() {
        val map = WalkMap(GridWorld().rule) { x, _, _ -> x < 3205 }
        assertEquals(WalkMap.UNKNOWN, map.distance(tile(3200, 3200), tile(3210, 3200), 50))
        assertNull(map.field(tile(3206, 3200)))
    }

    @Test
    fun `server walk facing follows the predicted path`() {
        val walk = ServerWalk(GridWorld().map())
        walk.clicked(tile(3200, 3200), tile(3210, 3200))
        val plan = walk.fires(tile(3201, 3200), null)
        val fires = plan.fires!!
        assertTrue(fires.all { it.direction == Direction.EAST })
        assertEquals(tile(3201, 3200), fires.first().tile)
        assertEquals(3, fires.size)
    }

    @Test
    fun `a walk that has not started yet is held for`() {
        var now = 0L
        val walk = ServerWalk(GridWorld().map()) { now }
        walk.clicked(tile(3200, 3200), tile(3210, 3200))
        assertTrue(walk.fires(tile(3200, 3200), null).hold)
        now = ServerWalk.START_WAIT_MS + 1
        assertTrue(!walk.fires(tile(3200, 3200), null).hold)
    }
}
