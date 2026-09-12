package com.projectx.puzzle.slide

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SlidePuzzleSolverTest {

    @Test
    fun alreadySolvedReturnsNoMoves() {
        val goal = IntArray(9) { it }
        val moves = SlidePuzzleSolver.solve(SlideBoard(3, 3, goal.copyOf(), blankId = 8), goal)
        assertEquals(emptyList(), moves)
    }

    @Test
    fun solvesTheLive1931Arrangement() {
        val tiles = intArrayOf(
            1, 2, 7, 3, 4,
            0, 6, 5, 8, 9,
            10, 16, 13, 14, 23,
            20, 22, 12, 11, 18,
            17, 15, 24, 21, 19,
        )
        val count = solveAndValidate(5, 5, tiles, blankId = 24)
        assertTrue(count in 1..400, "unexpected move count $count")
    }

    @Test
    fun solvesRandomSolvableBoardsAcrossSizes() {
        val rng = Random(0xC0FFEEL)
        val sizes = listOf(2 to 2, 2 to 3, 3 to 2, 3 to 3, 4 to 4, 5 to 5, 4 to 5, 5 to 4, 6 to 6)
        for ((rows, cols) in sizes) {
            repeat(200) {
                val (tiles, blankId) = scramble(rows, cols, rng)
                solveAndValidate(rows, cols, tiles, blankId)
            }
        }
    }

    /** Solves the board, asserts every move is legal and the final state is the goal. Returns move count. */
    private fun solveAndValidate(rows: Int, cols: Int, tiles: IntArray, blankId: Int): Int {
        val goal = IntArray(rows * cols) { it }
        val moves = SlidePuzzleSolver.solve(SlideBoard(rows, cols, tiles.copyOf(), blankId), goal)
        assertNotNull(moves, "solver returned null for ${tiles.toList()}")

        val work = tiles.copyOf()
        var blank = work.indexOf(blankId)
        for ((i, m) in moves.withIndex()) {
            assertEquals(blank, m.blankSlot, "move $i blankSlot mismatch")
            assertTrue(adjacent(m.tileSlot, m.blankSlot, cols), "move $i not adjacent (${m.tileSlot}->${m.blankSlot})")
            assertTrue(work[m.tileSlot] != blankId, "move $i moves the blank itself")
            assertEquals(SlidePuzzleSolver.directionBetween(m.tileSlot, m.blankSlot, cols), m.direction, "move $i direction")
            work[m.blankSlot] = work[m.tileSlot]
            work[m.tileSlot] = blankId
            blank = m.tileSlot
        }
        assertTrue(work.contentEquals(goal), "not solved: ${work.toList()} for start ${tiles.toList()}")
        return moves.size
    }

    private fun scramble(rows: Int, cols: Int, rng: Random): Pair<IntArray, Int> {
        val n = rows * cols
        val blankId = n - 1
        val t = IntArray(n) { it }
        var blank = n - 1
        var last = -1
        repeat(n * n * 4) {
            val nbs = neighbors(blank, rows, cols).filter { it != last }
            val nb = nbs[rng.nextInt(nbs.size)]
            t[blank] = t[nb]
            t[nb] = blankId
            last = blank
            blank = nb
        }
        return t to blankId
    }

    private fun neighbors(slot: Int, rows: Int, cols: Int): List<Int> {
        val r = slot / cols
        val c = slot % cols
        val out = ArrayList<Int>(4)
        if (r > 0) out.add(slot - cols)
        if (r < rows - 1) out.add(slot + cols)
        if (c > 0) out.add(slot - 1)
        if (c < cols - 1) out.add(slot + 1)
        return out
    }

    private fun adjacent(a: Int, b: Int, cols: Int): Boolean {
        val d = a - b
        return d == cols || d == -cols || (d == 1 && a / cols == b / cols) || (d == -1 && a / cols == b / cols)
    }
}
