package com.projectx.script.impl.trent.clue

private const val STATES = 3

/**
 * Lockboxes: an NxN grid of combat-style icons where clicking a square advances it and its four
 * orthogonal neighbours by one style, wrapping 2 -> 0 and clipping at the edges. The box opens when
 * every square shows the same style, and which style does not matter.
 *
 * Clicks commute and a square returns to itself after three presses, so this is a linear system over
 * GF(3): solve `A x = target - board` for the per-square press counts, where column j of A is the
 * plus-shaped stencil of square j. The board is solved for all three target styles and the cheapest
 * answer wins.
 */
class LockboxPlan(val target: Int, val presses: IntArray) {
    val totalPresses get() = presses.sum()
}

object LockboxSolver {

    fun solve(board: IntArray, size: Int): LockboxPlan? {
        require(board.size == size * size) { "board ${board.size} != ${size * size}" }
        val stencils = Array(size * size) { stencil(it, size) }
        return (0 until STATES)
            .mapNotNull { target -> cheapest(board, size, stencils, target) }
            .minByOrNull { it.totalPresses }
    }

    /** Squares this press advances: the square itself plus its orthogonal neighbours, clipped at the edges. */
    fun stencil(square: Int, size: Int): IntArray {
        val row = square / size
        val column = square % size
        return buildList {
            add(square)
            if (row > 0) add(square - size)
            if (row < size - 1) add(square + size)
            if (column > 0) add(square - 1)
            if (column < size - 1) add(square + 1)
        }.toIntArray()
    }

    private fun cheapest(board: IntArray, size: Int, stencils: Array<IntArray>, target: Int): LockboxPlan? {
        val cells = size * size
        val rows = Array(cells) { IntArray(cells + 1) }
        for (press in 0 until cells) for (affected in stencils[press]) rows[affected][press] = 1
        for (cell in 0 until cells) rows[cell][cells] = Math.floorMod(target - board[cell], STATES)

        val pivots = reduce(rows, cells) ?: return null
        val free = (0 until cells).filterNot { it in pivots }
        // A press count is 0..2, so the free variables span 3^free assignments; the grid's null space is
        // only a few dimensions, which keeps the exhaustive search over them trivial.
        var best: LockboxPlan? = null
        forEachAssignment(free.size) { assignment ->
            val presses = IntArray(cells)
            free.forEachIndexed { index, column -> presses[column] = assignment[index] }
            pivots.forEachIndexed { rowIndex, column ->
                var value = rows[rowIndex][cells]
                for (f in free) value -= rows[rowIndex][f] * presses[f]
                presses[column] = Math.floorMod(value, STATES)
            }
            val plan = LockboxPlan(target, presses)
            if (best == null || plan.totalPresses < best!!.totalPresses) best = plan
        }
        return best
    }

    /** Gauss-Jordan over GF(3); returns the pivot column per row, or null when the system is inconsistent. */
    private fun reduce(rows: Array<IntArray>, cells: Int): List<Int>? {
        val pivots = ArrayList<Int>(cells)
        var rank = 0
        for (column in 0 until cells) {
            val pivot = (rank until rows.size).firstOrNull { rows[it][column] % STATES != 0 } ?: continue
            val swap = rows[rank]; rows[rank] = rows[pivot]; rows[pivot] = swap
            val inverse = if (rows[rank][column] % STATES == 1) 1 else 2
            for (k in 0..cells) rows[rank][k] = Math.floorMod(rows[rank][k] * inverse, STATES)
            for (other in rows.indices) {
                val factor = rows[other][column] % STATES
                if (other == rank || factor == 0) continue
                for (k in 0..cells) rows[other][k] = Math.floorMod(rows[other][k] - factor * rows[rank][k], STATES)
            }
            pivots.add(column)
            if (++rank == rows.size) break
        }
        val inconsistent = (rank until rows.size).any { row ->
            (0 until cells).all { rows[row][it] % STATES == 0 } && rows[row][cells] % STATES != 0
        }
        return if (inconsistent) null else pivots
    }

    private inline fun forEachAssignment(count: Int, body: (IntArray) -> Unit) {
        val assignment = IntArray(count)
        val total = generateSequence(1) { it * STATES }.elementAt(count)
        repeat(total) { index ->
            var remaining = index
            for (i in 0 until count) {
                assignment[i] = remaining % STATES
                remaining /= STATES
            }
            body(assignment)
        }
    }
}
