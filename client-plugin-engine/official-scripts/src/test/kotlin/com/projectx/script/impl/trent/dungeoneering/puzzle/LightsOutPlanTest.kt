package com.projectx.script.impl.trent.dungeoneering.puzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import world.gregs.voidps.type.Tile

/**
 * The board here is the live 5x5 read out of room (754,271): 15 green, 10 yellow, edge-clipped plus. It is
 * the board the bot 2-cycled two tiles on forever.
 */
class LightsOutPlanTest {

    private val plus = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private val green = setOf(
        12070 to 4342, 12070 to 4343, 12070 to 4344, 12070 to 4345,
        12071 to 4342, 12071 to 4344, 12071 to 4345, 12071 to 4346,
        12072 to 4344, 12072 to 4345,
        12073 to 4342, 12073 to 4344, 12073 to 4345,
        12074 to 4342, 12074 to 4343,
    )
    private val yellow = setOf(
        12070 to 4346,
        12071 to 4343,
        12072 to 4342, 12072 to 4343, 12072 to 4346,
        12073 to 4343, 12073 to 4346,
        12074 to 4344, 12074 to 4345, 12074 to 4346,
    )

    private val board: Map<Tile, Boolean> =
        (green.map { Tile.of(it.first, it.second, 0) to true } +
            yellow.map { Tile.of(it.first, it.second, 0) to false }).toMap()

    private fun apply(plan: List<Tile>): Map<Tile, Boolean> =
        plan.fold(board) { acc, press -> LightsOutPuzzle.flip(acc, press, plus) }

    @Test
    fun `the live board is solvable and every press is planned once`() {
        val plan = LightsOutPuzzle.planFor(board, plus)
        assertNotNull(plan)
        assertEquals(25, board.size)
        // A tile pressed twice is the identity, so a correct solution never repeats one — which is exactly
        // what makes the A-B-A-B cycle impossible once presses come from a committed plan.
        assertEquals(plan!!.size, plan.toSet().size)
    }

    @Test
    fun `executing the whole plan leaves the board one colour`() {
        val plan = LightsOutPuzzle.planFor(board, plus)!!
        assertEquals(1, apply(plan).values.toSet().size)
    }

    @Test
    fun `the null space sweep beats the free-variables-zero solution`() {
        // Straight Gaussian elimination lands on 11 presses for this board. The two quiet patterns of a 5x5
        // plus admit a five-press set for the same target, and every press saved is a walk across the room.
        val plan = LightsOutPuzzle.planFor(board, plus)!!
        assertEquals(5, plan.size)
    }

    @Test
    fun `a board already one colour needs no presses`() {
        val lit = board.mapValues { true }
        assertEquals(0, LightsOutPuzzle.planFor(lit, plus)?.size)
    }
}
