package com.projectx.traversal.walk

import com.projectx.pathfinder.GridWorld
import com.projectx.pathfinder.GridWorld.Companion.tile
import com.projectx.pathfinder.ServerWalk
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalkPlanningTest {

    private fun straight(fromX: Int, toX: Int, y: Int = 3200): List<Tile> = (fromX..toX).map { tile(it, y) }

    @Test
    fun `the click is the furthest route tile the player walks to without a detour`() {
        val map = GridWorld().map()
        val pick = ClickPicker.choose(map, straight(3200, 3260), tile(3200, 3200), stepLimit = 15, fromIndex = 0)!!
        assertEquals(tile(3215, 3200), pick.tile)
        assertTrue(pick.onRoute)
    }

    @Test
    fun `a blocked route tile is never clicked`() {
        val world = GridWorld().block(3215, 3200)
        val pick = ClickPicker.choose(world.map(), straight(3200, 3260), tile(3200, 3200), stepLimit = 15, fromIndex = 0)!!
        assertEquals(tile(3214, 3200), pick.tile)
    }

    @Test
    fun `a route tile reached only by a long detour is not this leg's click`() {
        val world = GridWorld().blockRect(3205, 3150, 3205, 3240)
        val route = straight(3200, 3204) + (3200..3226).map { tile(3206, it) }
        val pick = ClickPicker.choose(world.map(), route, tile(3200, 3200), stepLimit = 15, fromIndex = 0)!!
        assertEquals(tile(3204, 3200), pick.tile)
    }

    @Test
    fun `standing at the end of the route picks nothing`() {
        val pick = ClickPicker.choose(GridWorld().map(), straight(3200, 3205), tile(3205, 3200), 15, 0)!!
        assertNull(pick.tile)
        assertTrue(pick.atEnd)
    }

    @Test
    fun `progress is not latched from far off the route`() {
        val route = straight(3200, 3260)
        val pick = ClickPicker.Pick(null, -1, 40, false, false)
        assertEquals(3, ClickPicker.advance(3, pick, route, tile(3240, 3300), 15))
        assertEquals(40, ClickPicker.advance(3, pick, route, tile(3240, 3201), 15))
    }

    @Test
    fun `stuck needs stillness and two unreachable checks apart`() {
        var now = 0L
        val stuck = StuckDetector { now }
        val here = tile(3200, 3200)
        assertFalse(stuck.stuck(here, false) { true })
        now = StuckDetector.STILL_MS
        assertFalse(stuck.stuck(here, false) { true })
        now += StuckDetector.CONFIRM_MS
        assertTrue(stuck.stuck(here, false) { true })
        assertFalse(stuck.stuck(tile(3201, 3200), false) { true })
    }

    @Test
    fun `a surge along a straight walk is cast and one at a bend is held`() {
        val map = GridWorld().map()
        val planner = HopPlanner(map)
        val route = straight(3200, 3260)
        val ahead = route[HopPlanner.SURGE_PROGRESS_AHEAD]
        val straightFires = listOf(ServerWalk.Fire(tile(3200, 3200), Direction.EAST), ServerWalk.Fire(tile(3201, 3200), Direction.EAST))
        val cast = planner.surgeOnWalk(straightFires, route, 0, ahead)
        assertTrue(cast.cast, cast.toString())
        assertEquals(tile(3210, 3200), cast.landing)

        val bend = listOf(ServerWalk.Fire(tile(3200, 3200), Direction.EAST), ServerWalk.Fire(tile(3201, 3201), Direction.NORTH_EAST))
        assertFalse(planner.surgeOnWalk(bend, route, 0, ahead).cast)
    }

    @Test
    fun `a surge that lands behind a wall gains nothing and is held`() {
        val world = GridWorld().blockRect(3206, 3190, 3206, 3200)
        val route = straight(3200, 3205) + (3201..3205).map { tile(3205, it) } + (3206..3240).map { tile(it, 3205) }
        val planner = HopPlanner(world.map())
        val choice = planner.surgeByLastStep(Direction.EAST, chained = true, route = route, nearest = 0, player = tile(3200, 3200), ahead = route[25])
        assertFalse(choice.cast, choice.toString())
    }

    @Test
    fun `a dive aims at the furthest route tile that saves enough`() {
        val planner = HopPlanner(GridWorld().map())
        val route = straight(3200, 3260)
        val choice = planner.dive(route, 0, tile(3200, 3200), emptyList(), route[HopPlanner.SURGE_PROGRESS_AHEAD])
        assertTrue(choice.cast, choice.toString())
        assertEquals(tile(3210, 3200), choice.landing)
        assertEquals(tile(3220, 3200), choice.aim)
    }

    @Test
    fun `a dive that is refused from the tile it fires from is held`() {
        // Open from the player's tile, but two steps on the walk turns into a dead end behind a wall.
        val world = GridWorld().blockRect(3195, 3203, 3215, 3203)
        val planner = HopPlanner(world.map())
        val route = (3200..3202).map { tile(3200, it) } + (3201..3230).map { tile(it, 3202) }
        val arming = listOf(tile(3200, 3204))
        assertNotNull(planner.armedShort(arming, tile(3210, 3202), route[25]))
    }

    @Test
    fun `the surge after a dive follows the dive's line`() {
        val planner = HopPlanner(GridWorld().map())
        val route = straight(3200, 3260)
        assertEquals(Direction.EAST, planner.chainedSurge(route, 0, tile(3200, 3200), tile(3210, 3200)))
    }

    @Test
    fun `near the route's end a short surge is enough`() {
        val planner = HopPlanner(GridWorld().map())
        val route = straight(3200, 3210)
        assertEquals(HopPlanner.SURGE_END_MIN_GAIN, planner.surgeNeed(route, 0, tile(3210, 3200)))
        assertEquals(HopPlanner.SURGE_MIN_GAIN, planner.surgeNeed(straight(3200, 3260), 0, tile(3210, 3200)))
    }

    @Test
    fun `the route's next two steps give its heading`() {
        val route = straight(3200, 3203) + listOf(tile(3204, 3201))
        assertEquals(Direction.EAST, HopPlanner.routeAhead(route, 0))
        assertNull(HopPlanner.routeAhead(route, 2))
        assertTrue(HopPlanner.walkRunsAlong(Direction.EAST, tile(3200, 3200), tile(3209, 3200)))
        assertFalse(HopPlanner.walkRunsAlong(Direction.EAST, tile(3200, 3200), tile(3209, 3203)))
    }

    @Test
    fun `a powerburst pays on a long straight and not on a short one`() {
        val planner = AccelerationPlanner(GridWorld().map())
        val long = straight(3200, 3290)
        val why = StringBuilder()
        assertNotNull(planner.evaluate(long, 0, tile(3200, 3200), 90.0, surge = true, dive = true, anyway = 0, why = why), why.toString())
        val short = straight(3200, 3215)
        assertNull(planner.evaluate(short, 0, tile(3200, 3200), 90.0, surge = true, dive = true, anyway = 0, why = StringBuilder()))
    }
}
