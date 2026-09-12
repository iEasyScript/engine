package com.projectx.script.impl.trent.clue

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LockboxSolverTest {

    private val size = 5

    private fun applied(board: IntArray, presses: IntArray): IntArray {
        val result = board.copyOf()
        presses.indices.forEach { square ->
            repeat(presses[square]) {
                LockboxSolver.stencil(square, size).forEach { result[it] = (result[it] + 1) % 3 }
            }
        }
        return result
    }

    @Test
    fun `a centre press moves its plus and a corner press only what is in bounds`() {
        assertContentEquals(intArrayOf(12, 7, 17, 11, 13), LockboxSolver.stencil(12, size))
        assertContentEquals(intArrayOf(0, 5, 1), LockboxSolver.stencil(0, size))
        assertContentEquals(intArrayOf(4, 9, 3), LockboxSolver.stencil(4, size))
    }

    @Test
    fun `an already uniform board needs no presses`() {
        val plan = assertNotNull(LockboxSolver.solve(IntArray(25) { 1 }, size))
        assertEquals(0, plan.totalPresses)
    }

    @Test
    fun `the live board solves and every square ends on one style`() {
        // Captured from interface 1933 mid-puzzle.
        val board = intArrayOf(
            1, 1, 1, 2, 0,
            1, 0, 0, 1, 2,
            0, 2, 2, 1, 0,
            2, 2, 2, 2, 1,
            0, 1, 2, 1, 0,
        )
        val plan = assertNotNull(LockboxSolver.solve(board, size))
        assertEquals(14, plan.totalPresses)
        val solved = applied(board, plan.presses)
        assertEquals(1, solved.toSet().size)
        assertTrue(plan.presses.all { it in 0..2 })
    }

    @Test
    fun `every reachable board solves back to uniform`() {
        // Boards reachable by pressing are exactly the solvable ones, so scrambling from uniform is a
        // fair generator - and it catches a solver that only handles the case it was written against.
        val random = java.util.Random(11)
        repeat(40) {
            val board = IntArray(25)
            repeat(30) {
                val square = random.nextInt(25)
                LockboxSolver.stencil(square, size).forEach { board[it] = (board[it] + 1) % 3 }
            }
            val plan = assertNotNull(LockboxSolver.solve(board, size))
            assertEquals(1, applied(board, plan.presses).toSet().size)
        }
    }
}
