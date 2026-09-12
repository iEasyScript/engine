package com.projectx.script.impl.trent.clue

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TowersSolverTest {

    private val grid = intArrayOf(
        2, 4, 5, 3, 1,
        1, 5, 3, 4, 2,
        4, 3, 1, 2, 5,
        3, 1, 2, 5, 4,
        5, 2, 4, 1, 3,
    )

    private val clues = TowerClues(
        top = intArrayOf(3, 2, 1, 3, 3),
        left = intArrayOf(3, 2, 2, 2, 1),
        bottom = intArrayOf(1, 3, 2, 2, 3),
        right = intArrayOf(3, 3, 1, 2, 3),
    )

    @Test
    fun `a fully clued board solves to its one grid`() {
        assertContentEquals(grid, assertNotNull(TowersSolver.solve(clues)))
        assertEquals(1, TowersSolver.solutions(clues).size)
    }

    @Test
    fun `a fully clued board can still admit more than one grid`() {
        // Captured live from trail17_skyscrapers. Every one of the twenty clues is given, yet two
        // Latin squares satisfy them - verified by checking all 161,280 5x5 Latin squares. Returning
        // only "the unique answer" here is what made the overlay draw nothing at all.
        val ambiguous = TowerClues(
            top = intArrayOf(2, 2, 3, 3, 1),
            left = intArrayOf(2, 2, 3, 1, 4),
            bottom = intArrayOf(2, 3, 2, 1, 4),
            right = intArrayOf(1, 2, 3, 3, 2),
        )
        val found = TowersSolver.solutions(ambiguous)
        assertEquals(2, found.size)
        assertNull(TowersSolver.solve(ambiguous))
        assertEquals(4, found[0].indices.count { found[0][it] != found[1][it] })
    }

    @Test
    fun `no clues at all leaves the board ambiguous`() {
        val blank = IntArray(5)
        assertNull(TowersSolver.solve(TowerClues(blank, blank, blank, blank)))
    }

    @Test
    fun `contradictory clues have no solution`() {
        val broken = TowerClues(
            top = intArrayOf(1, 1, 1, 1, 1),
            left = intArrayOf(5, 5, 5, 5, 5),
            bottom = IntArray(5),
            right = IntArray(5),
        )
        assertNull(TowersSolver.solve(broken))
    }

    @Test
    fun `a mirrored edge reading does not accidentally also solve`() {
        assertNull(TowersSolver.solve(clues.mirrored(bottomReversed = true, rightReversed = true)))
    }
}
