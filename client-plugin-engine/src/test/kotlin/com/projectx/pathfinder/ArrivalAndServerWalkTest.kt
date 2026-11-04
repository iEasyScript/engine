package com.projectx.pathfinder

import com.projectx.pathfinder.GridWorld.Companion.tile
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrivalAndServerWalkTest {

    @Test
    fun `an arrival ends once every sampled tile has loaded`() {
        var now = 1_000L
        val watch = ArrivalWatch { now }
        watch.teleported()
        assertTrue(watch.arriving(3))
        now += 100
        assertFalse(watch.arriving(0))
        assertFalse(watch.arriving(3))
    }

    @Test
    fun `an arrival ends once the count still unloaded has held still, as at a dungeon's edge`() {
        var now = 1_000L
        val watch = ArrivalWatch { now }
        watch.teleported()
        assertTrue(watch.arriving(2))
        now += ArrivalWatch.STABLE_MS - 1
        assertTrue(watch.arriving(2))
        now += 1
        assertFalse(watch.arriving(2))
    }

    @Test
    fun `a falling unloaded count restarts the stable wait, but never past the cap`() {
        var now = 1_000L
        val watch = ArrivalWatch { now }
        watch.teleported()
        assertTrue(watch.arriving(4))
        now += 1_500
        assertTrue(watch.arriving(3))
        now += 1_500
        assertTrue(watch.arriving(2))
        now += 1_500
        assertTrue(watch.arriving(1))
        now += 600
        assertFalse(watch.arriving(1))
        assertTrue(now - 1_000L >= ArrivalWatch.MAX_MS)
    }

    @Test
    fun `nothing is arriving before a teleport`() {
        assertFalse(ArrivalWatch { 0L }.arriving(5))
    }

    @Test
    fun `a jump beyond a run's reach is a teleport`() {
        assertTrue(LiveWalkMap.isTeleport(tile(3200, 3200), tile(3213, 3200), 0))
        assertFalse(LiveWalkMap.isTeleport(tile(3200, 3200), tile(3214, 3200), 600))
        assertTrue(LiveWalkMap.isTeleport(tile(3200, 3200), tile(3215, 3200), 600))
        assertFalse(LiveWalkMap.isTeleport(tile(3200, 3200), Tile.of(3205, 3200, 1), 0))
        assertTrue(LiveWalkMap.isTeleport(tile(3200, 3200), Tile.of(3213, 3200, 1), 600))
    }

    @Test
    fun `another synthetic action after the walk click drops the predicted walk`() {
        var fires = 0L
        val walk = ServerWalk(GridWorld().map(), { 0L }) { fires }
        fires++
        walk.clicked(tile(3200, 3200), tile(3205, 3200))
        assertEquals(tile(3205, 3200), walk.click)
        assertNotNull(walk.ahead(tile(3201, 3200)))
        fires++
        assertNull(walk.click)
        assertNull(walk.ahead(tile(3201, 3200)))
        assertNull(walk.fires(tile(3201, 3200), null).fires)
    }

    @Test
    fun `the walker's own next click keeps the walk it was on`() {
        var fires = 0L
        val walk = ServerWalk(GridWorld().map(), { 0L }) { fires }
        fires++
        walk.clicked(tile(3200, 3200), tile(3210, 3200))
        fires++
        walk.clicked(tile(3203, 3200), tile(3215, 3200))
        assertEquals(tile(3215, 3200), walk.click)
        assertNotNull(walk.fires(tile(3204, 3200), null).fires)
    }

    @Test
    fun `a walk clicked mid-hop is not predicted again from the landing once something else fired`() {
        var fires = 0L
        val walk = ServerWalk(GridWorld().map(), { 0L }) { fires }
        walk.hopCast()
        fires++
        walk.clicked(tile(3200, 3200), tile(3220, 3200))
        fires++
        walk.hopSettled(tile(3210, 3200), null)
        assertNull(walk.click)
    }
}
