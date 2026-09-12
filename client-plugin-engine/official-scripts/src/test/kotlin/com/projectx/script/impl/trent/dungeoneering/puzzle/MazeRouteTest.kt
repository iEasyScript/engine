package com.projectx.script.impl.trent.dungeoneering.puzzle

import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MazeRouteTest {

    private val noGates = emptyList<MazeRoute.Gate<String>>()

    private fun tile(x: Int, y: Int) = Tile.of(x, y, 0)

    // Walkable interior [0, size) x [0, size), cut by walls given as the tile pairs they separate.
    private fun room(size: Int, vararg walls: Pair<Pair<Int, Int>, Pair<Int, Int>>): (Tile, Int, Int) -> Boolean {
        val sealed = walls.flatMap { (a, b) -> listOf(a to b, b to a) }.toHashSet()
        return { from, dx, dy ->
            val to = (from.x + dx) to (from.y + dy)
            to.first in 0 until size && to.second in 0 until size && ((from.x to from.y) to to) !in sealed
        }
    }

    private fun gate(door: String, near: Pair<Int, Int>, far: Pair<Int, Int>) =
        MazeRoute.Gate(door, tile(near.first, near.second), tile(far.first, far.second))

    @Test
    fun `walkable target needs no crossing`() {
        val crossed = MazeRoute.crossings(tile(0, 0), noGates, room(4)) { it == tile(3, 3) }
        assertEquals(noGates, crossed)
    }

    @Test
    fun `sealed target is unreachable without a gate`() {
        val walls = arrayOf(
            (1 to 0) to (2 to 0), (1 to 1) to (2 to 1), (1 to 2) to (2 to 2), (1 to 3) to (2 to 3),
        )
        assertNull(MazeRoute.crossings(tile(0, 0), noGates, room(4, *walls)) { it == tile(3, 3) })
    }

    @Test
    fun `gate in the wall is the one crossing reported`() {
        val walls = arrayOf(
            (1 to 0) to (2 to 0), (1 to 1) to (2 to 1), (1 to 2) to (2 to 2), (1 to 3) to (2 to 3),
        )
        val gates = listOf(gate("door", 1 to 2, 2 to 2))
        val crossed = MazeRoute.crossings(tile(0, 0), gates, room(4, *walls)) { it == tile(3, 3) }
        assertTrue(crossed != null && crossed.size == 1)
        assertEquals("door", crossed.single().door)
        assertEquals(tile(1, 2), crossed.single().near)
        assertEquals(tile(2, 2), crossed.single().far)
    }

    @Test
    fun `a gate is usable from either side`() {
        val walls = arrayOf(
            (1 to 0) to (2 to 0), (1 to 1) to (2 to 1), (1 to 2) to (2 to 2), (1 to 3) to (2 to 3),
        )
        val gates = listOf(gate("door", 1 to 2, 2 to 2))
        val crossed = MazeRoute.crossings(tile(3, 3), gates, room(4, *walls)) { it == tile(0, 0) }
        assertTrue(crossed != null && crossed.size == 1)
        assertEquals(tile(2, 2), crossed.single().near)
        assertEquals(tile(1, 2), crossed.single().far)
    }

    @Test
    fun `walking round beats crossing a gate`() {
        // Nothing is sealed, so the gate is a pure detour the click-minimising search must ignore.
        val gates = listOf(gate("door", 0 to 0, 1 to 0))
        val crossed = MazeRoute.crossings(tile(0, 0), gates, room(4)) { it == tile(3, 0) }
        assertEquals(noGates, crossed)
    }

    @Test
    fun `chained gates come back in traversal order`() {
        val walls = arrayOf(
            (0 to 0) to (0 to 1), (1 to 0) to (1 to 1), (2 to 0) to (2 to 1), (3 to 0) to (3 to 1),
            (0 to 1) to (0 to 2), (1 to 1) to (1 to 2), (2 to 1) to (2 to 2), (3 to 1) to (3 to 2),
        )
        val gates = listOf(gate("south", 2 to 0, 2 to 1), gate("north", 1 to 1, 1 to 2))
        val crossed = MazeRoute.crossings(tile(0, 0), gates, room(4, *walls)) { it == tile(3, 3) }
        assertEquals(listOf("south", "north"), crossed?.map { it.door })
    }

    @Test
    fun `adjacency arrival stops beside a blocked target`() {
        val crossed = MazeRoute.crossings(tile(0, 0), noGates, room(4)) {
            abs(it.x - 3) + abs(it.y - 3) <= 1
        }
        assertEquals(noGates, crossed)
    }
}
