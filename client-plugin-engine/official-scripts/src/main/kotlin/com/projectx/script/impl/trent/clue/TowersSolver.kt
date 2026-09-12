package com.projectx.script.impl.trent.clue

/**
 * Skyscrapers ("Towers"): fill an NxN grid with heights 1..N so no height repeats in a row or column,
 * and every edge clue equals the number of towers visible looking down that line - a tower is visible
 * when it is taller than everything between it and the viewer. A clue of 0 means "no clue given".
 *
 * Clue arrays are indexed in screen order along their edge: [top]/[bottom] by column left to right,
 * [left]/[right] by row top to bottom.
 */
class TowerClues(val top: IntArray, val left: IntArray, val bottom: IntArray, val right: IntArray) {
    val size get() = top.size

    fun mirrored(bottomReversed: Boolean, rightReversed: Boolean) = TowerClues(
        top,
        left,
        if (bottomReversed) bottom.reversedArray() else bottom,
        if (rightReversed) right.reversedArray() else right,
    )

    fun isEmpty() = (top + left + bottom + right).all { it == 0 }
}

object TowersSolver {

    /**
     * Every grid satisfying [clues], in row-major order, capped at [limit].
     *
     * A fully clued board is usually unique but is not guaranteed to be - real puzzles turn up that
     * admit two grids under the rules the interface itself states - so callers get the whole set and
     * decide what to do with an ambiguous cell rather than being handed a false certainty.
     */
    fun solutions(clues: TowerClues, limit: Int = MAX_SOLUTIONS): List<IntArray> {
        val found = ArrayList<IntArray>(limit)
        search(clues, IntArray(clues.size * clues.size), 0, found, limit)
        return found
    }

    /** The one grid satisfying [clues], or null when there is no solution or more than one. */
    fun solve(clues: TowerClues): IntArray? = solutions(clues, limit = 2).singleOrNull()

    private const val MAX_SOLUTIONS = 8

    private fun search(clues: TowerClues, grid: IntArray, cell: Int, found: MutableList<IntArray>, limit: Int) {
        if (found.size >= limit) return
        val n = clues.size
        if (cell == grid.size) {
            found.add(grid.copyOf())
            return
        }
        val row = cell / n
        val col = cell % n
        for (height in 1..n) {
            if ((0 until col).any { grid[row * n + it] == height }) continue
            if ((0 until row).any { grid[it * n + col] == height }) continue
            grid[cell] = height
            if (consistent(clues, grid, row, col)) search(clues, grid, cell + 1, found, limit)
            grid[cell] = 0
        }
    }

    private fun consistent(clues: TowerClues, grid: IntArray, row: Int, col: Int): Boolean {
        val n = clues.size
        val rowComplete = col == n - 1
        val colComplete = row == n - 1
        val rowLine = IntArray(n) { grid[row * n + it] }
        val colLine = IntArray(n) { grid[it * n + col] }
        return matches(clues.left[row], rowLine, rowComplete) &&
                matches(clues.right[row], rowLine.reversedArray(), rowComplete) &&
                matches(clues.top[col], colLine, colComplete) &&
                matches(clues.bottom[col], colLine.reversedArray(), colComplete)
    }

    /**
     * A partially filled line is pruned rather than rejected: the towers placed so far already fix a
     * lower bound on visibility, and every remaining blank can add at most one more.
     */
    private fun matches(clue: Int, line: IntArray, complete: Boolean): Boolean {
        if (clue == 0) return true
        var visible = 0
        var tallest = 0
        var blanks = 0
        for (height in line) {
            if (height == 0) {
                blanks++
                continue
            }
            if (blanks > 0) return true
            if (height > tallest) {
                tallest = height
                visible++
            }
        }
        if (complete && blanks == 0) return visible == clue
        return visible <= clue && visible + blanks >= clue
    }
}
