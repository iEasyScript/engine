package com.projectx.webwalker

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.type.Tile
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plans real routes over a copy of the game cache. Skipped unless `-Dprojectx.testCache=<dir>` names a directory
 * of `.jcache` files; never point it at the live client cache, copy it first.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebPathfinderCacheTest {

    @BeforeAll
    fun openCache() {
        val dir = System.getProperty("projectx.testCache")
        assumeTrue(dir != null && Files.isDirectory(Paths.get(dir)), "projectx.testCache not set")
        Cache.init(Paths.get(dir!!), readOnly = true)
    }

    @Test
    fun `plans Lumbridge to Varrock west bank`() {
        assertRoute(3222, 3218, 3185, 3436)
    }

    @Test
    fun `plans Lumbridge to Draynor bank`() {
        assertRoute(3222, 3218, 3092, 3243)
    }

    @Test
    fun `plans onward from lodestone arrival tiles`() {
        assertRoute(3233, 3221, 3092, 3243)
        assertRoute(3214, 3376, 3185, 3436)
        assertRoute(2967, 3403, 3013, 3355)
    }

    @Test
    fun `gives up on an island in bounded time`() {
        val started = System.nanoTime()
        val result = WebPathfinder().find(3222, 3218, 0, 2918, 3175, 0, 2)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("[WebWalk] Lumbridge -> Karamja: $result, ${millis}ms, ${WebCollision.loadedSquareCount()} squares")
        assertTrue(result.status == WebWalkStatus.NO_PATH || result.status == WebWalkStatus.TOO_FAR, result.toString())
        assertTrue(millis < 30_000, "search took ${millis}ms")
    }

    @Test
    fun `routes through doors`() {
        WebPathfinder().find(3222, 3218, 0, 3185, 3436, 0, 2)
        val sides = arrayOf(intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(0, -1))
        val doors = WebCollision.doors().entries.filter { Tile(it.key).plane == 0 }.take(60)
        assertTrue(doors.isNotEmpty(), "no doors indexed around Lumbridge")

        var throughDoor = 0
        for ((id, side) in doors) {
            val door = Tile(id)
            val result = WebPathfinder().find(door.x, door.y, 0, door.x + sides[side][0], door.y + sides[side][1], 0, 0)
            if ((result.path?.doorCount ?: 0) > 0) throughDoor++
        }
        println("[WebWalk] ${WebCollision.doors().size} doors indexed; $throughDoor of ${doors.size} sampled crossings went through the door")
        assertTrue(throughDoor > 0, "no route crossed a door")
    }

    @Test
    fun `routes to another plane by taking a link`() {
        // Whichever cross-plane link the set has nearest Lumbridge, so the search only loads squares around it.
        val link = WebLinks.all
            .filter { it.from.plane != it.to.plane }
            .minByOrNull { max(abs(it.from.centreX - 3222), abs(it.from.centreY - 3218)) }
        assumeTrue(link != null, "the link set has no cross-plane link")
        val from = link!!.from
        val to = link.to

        val result = WebPathfinder().find(from.centreX, from.centreY, from.plane, to.centreX, to.centreY, to.plane, 4)
        println("[WebWalk] $link: $result, ${result.path}")

        assertEquals(WebWalkStatus.PATH_FOUND, result.status, result.message)
        val path = assertNotNull(result.path)
        assertTrue(path.changesPlane, "the route never left plane ${from.plane}")
        assertTrue(path.linkCount > 0, "the route changed plane without taking a link")
        assertEquals(to.plane, path.getPlane(path.lastIndex), "the route did not finish on the destination plane")
    }

    @Test
    fun `reports a destination off the world map`() {
        val result = WebPathfinder().find(3222, 3218, 0, 20, 20, 0, 2)
        assertEquals(WebWalkStatus.NO_PATH, result.status)
    }

    private fun assertRoute(startX: Int, startY: Int, destX: Int, destY: Int) {
        val started = System.nanoTime()
        val result = WebPathfinder().find(startX, startY, 0, destX, destY, 0, 2)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("[WebWalk] $startX,$startY -> $destX,$destY: $result, ${result.path}, ${millis}ms, ${WebCollision.loadedSquareCount()} squares")

        assertEquals(WebWalkStatus.PATH_FOUND, result.status, result.message)
        val path = assertNotNull(result.path)
        assertEquals(startX, path.getX(0))
        assertEquals(startY, path.getY(0))
        assertTrue(max(abs(path.getX(path.lastIndex) - destX), abs(path.getY(path.lastIndex) - destY)) <= 2)
        for (i in 1 until path.size) {
            // A link jumps: a staircase step is not adjacent to the one before it, and may change plane.
            if (path.linkAt(i) != null) continue
            assertEquals(path.getPlane(i - 1), path.getPlane(i), "step $i changed plane without a link")
            val step = max(abs(path.getX(i) - path.getX(i - 1)), abs(path.getY(i) - path.getY(i - 1)))
            assertEquals(1, step, "step $i is not adjacent to the one before it")
        }
    }
}
