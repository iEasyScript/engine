package com.projectx.puzzle.slide

import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Solves arbitrary NxM sliding-tile puzzles into a move sequence.
 *
 * Boards up to 3x3 are solved optimally with IDA* (Manhattan heuristic). Larger boards use the
 * classic human reduction — place the top row and left column with blank-walking macros, shrinking
 * the working mapSquare until a <=3x3 residual remains, then finish that residual with IDA*. This is
 * the same staged-reduction strategy RuneLite's `IDAStarMM` uses for the 5x5 puzzle box, and it
 * solves any solvable board of any size well within the time budget.
 *
 * Assumes the blank's goal cell is the bottom-right corner (true for an identity goal, which is what
 * the RS puzzle box uses).
 */
object SlidePuzzleSolver {

    fun solve(board: SlideBoard, goal: IntArray, budgetMs: Long = 1000L): List<SlideMove>? {
        require(goal.size == board.size) { "goal size ${goal.size} != board size ${board.size}" }
        if (board.isSolved(goal)) return emptyList()
        val deadline = System.nanoTime() + budgetMs * 1_000_000L
        val goalPos = goalPositions(goal)

        if (board.rows <= 3 && board.cols <= 3) {
            val allowAll = BooleanArray(board.size) { true }
            return IdaStar(board.rows, board.cols, board.tiles.copyOf(), board.blankId, goal, goalPos, allowAll, deadline).search()
        }

        val reducer = Reducer(board, goal)
        if (!reducer.reduce()) return null
        val tail = IdaStar(
            board.rows, board.cols, reducer.tiles, board.blankId, goal, goalPos,
            BooleanArray(board.size) { !reducer.frozen[it] }, deadline,
        ).search() ?: return null
        return reducer.moves + tail
    }

    private fun goalPositions(goal: IntArray): IntArray {
        var max = 0
        for (g in goal) if (g > max) max = g
        val pos = IntArray(max + 1) { -1 }
        for (slot in goal.indices) pos[goal[slot]] = slot
        return pos
    }

    internal fun directionBetween(fromSlot: Int, toSlot: Int, cols: Int): Direction = when (toSlot - fromSlot) {
        -cols -> Direction.UP
        cols -> Direction.DOWN
        -1 -> Direction.LEFT
        1 -> Direction.RIGHT
        else -> error("slots $fromSlot and $toSlot are not orthogonally adjacent (cols=$cols)")
    }
}

/**
 * Mutable reduction engine. Places the top row then the left column repeatedly, freezing each placed
 * cell, until the unfrozen mapSquare is at most 3x3. All blank movement is done via BFS over unfrozen
 * cells, so placed tiles are never disturbed.
 */
private class Reducer(board: SlideBoard, val goal: IntArray) {
    val rows = board.rows
    val cols = board.cols
    val n = board.size
    val tiles = board.tiles.copyOf()
    val blankId = board.blankId
    var blank = tiles.indexOf(blankId)
    val frozen = BooleanArray(n)
    val moves = ArrayList<SlideMove>()

    private var top = 0
    private var left = 0

    private fun rowOf(s: Int) = s / cols
    private fun colOf(s: Int) = s % cols
    private fun slot(r: Int, c: Int) = r * cols + c

    fun reduce(): Boolean {
        while (rows - top > 3 || cols - left > 3) {
            val ok = if (rows - top >= cols - left) solveTopRow().also { top++ } else solveLeftCol().also { left++ }
            if (!ok) return false
        }
        return true
    }

    private fun slideBlankInto(neighbor: Int) {
        val dir = SlidePuzzleSolver.directionBetween(neighbor, blank, cols)
        moves += SlideMove(neighbor, blank, dir)
        tiles[blank] = tiles[neighbor]
        tiles[neighbor] = blankId
        blank = neighbor
    }

    private fun neighbors(s: Int): IntArray {
        val r = rowOf(s)
        val c = colOf(s)
        var count = 0
        val tmp = IntArray(4)
        if (r > 0) tmp[count++] = s - cols
        if (r < rows - 1) tmp[count++] = s + cols
        if (c > 0) tmp[count++] = s - 1
        if (c < cols - 1) tmp[count++] = s + 1
        return tmp.copyOf(count)
    }

    private fun solveTopRow(): Boolean {
        val r = top
        for (c in left..cols - 3) {
            if (!placeOne(goal[slot(r, c)], slot(r, c))) return false
            frozen[slot(r, c)] = true
        }
        if (!placeTwo(slot(r, cols - 2), slot(r, cols - 1))) return false
        frozen[slot(r, cols - 2)] = true
        frozen[slot(r, cols - 1)] = true
        return true
    }

    private fun solveLeftCol(): Boolean {
        val c = left
        for (r in top..rows - 3) {
            if (!placeOne(goal[slot(r, c)], slot(r, c))) return false
            frozen[slot(r, c)] = true
        }
        if (!placeTwo(slot(rows - 2, c), slot(rows - 1, c))) return false
        frozen[slot(rows - 2, c)] = true
        frozen[slot(rows - 1, c)] = true
        return true
    }

    /**
     * Bring the tile that belongs at [target] to that cell via BFS over (tilePosition, blankPosition),
     * treating every other unfrozen tile as interchangeable, then replay the discovered blank path on
     * the real board. BFS always finds a route when one exists, so there is no corner routing to hand-roll.
     */
    private fun placeOne(piece: Int, target: Int): Boolean {
        val start = tiles.indexOf(piece)
        if (start == target) return true
        val prev = IntArray(n * n) { -1 }
        val mv = IntArray(n * n)
        val startState = start * n + blank
        prev[startState] = startState
        val q = ArrayDeque<Int>()
        q.add(startState)
        var goalState = -1
        while (q.isNotEmpty()) {
            val st = q.poll()
            val pos = st / n
            val bl = st % n
            if (pos == target) { goalState = st; break }
            for (nb in neighbors(bl)) {
                if (frozen[nb]) continue
                val ns = (if (nb == pos) bl else pos) * n + nb
                if (prev[ns] == -1) { prev[ns] = st; mv[ns] = nb; q.add(ns) }
            }
        }
        if (goalState < 0) return false
        replay(prev, mv, startState, goalState)
        return true
    }

    /**
     * Bring the two tiles that belong at [targetA]/[targetB] to those cells together via BFS over
     * (tileA, tileB, blank). Placing the last two of a line as a pair — rather than one then the other —
     * is what avoids the classic corner deadlock; BFS finds the rotation on its own.
     */
    private fun placeTwo(targetA: Int, targetB: Int): Boolean {
        val a0 = tiles.indexOf(goal[targetA])
        val b0 = tiles.indexOf(goal[targetB])
        if (a0 == targetA && b0 == targetB) return true
        val prev = IntArray(n * n * n) { -1 }
        val mv = IntArray(n * n * n)
        val startState = (a0 * n + b0) * n + blank
        prev[startState] = startState
        val q = ArrayDeque<Int>()
        q.add(startState)
        var goalState = -1
        while (q.isNotEmpty()) {
            val st = q.poll()
            val bl = st % n
            val b = (st / n) % n
            val a = st / (n * n)
            if (a == targetA && b == targetB) { goalState = st; break }
            for (nb in neighbors(bl)) {
                if (frozen[nb]) continue
                val na = if (nb == a) bl else a
                val nbB = if (nb == b) bl else b
                val ns = (na * n + nbB) * n + nb
                if (prev[ns] == -1) { prev[ns] = st; mv[ns] = nb; q.add(ns) }
            }
        }
        if (goalState < 0) return false
        replay(prev, mv, startState, goalState)
        return true
    }

    private fun replay(prev: IntArray, mv: IntArray, startState: Int, goalState: Int) {
        val seq = ArrayList<Int>()
        var s = goalState
        while (s != startState) { seq.add(mv[s]); s = prev[s] }
        for (i in seq.indices.reversed()) slideBlankInto(seq[i])
    }
}

/** Optimal IDA* over the cells flagged in [allowed] (blank only ever swaps with allowed neighbors). */
private class IdaStar(
    val rows: Int,
    val cols: Int,
    start: IntArray,
    val blankId: Int,
    val goal: IntArray,
    val goalPos: IntArray,
    val allowed: BooleanArray,
    val deadline: Long,
) {
    private val n = rows * cols
    private val tiles = start.copyOf()
    private var blank = tiles.indexOf(blankId)
    private val path = ArrayList<SlideMove>()
    private var aborted = false

    private companion object { const val FOUND = -1 }

    fun search(): List<SlideMove>? {
        if (solved()) return emptyList()
        var bound = heuristic()
        while (true) {
            val t = dfs(0, bound, -1)
            if (aborted) return null
            if (t == FOUND) return ArrayList(path)
            if (t == Int.MAX_VALUE) return null
            bound = t
        }
    }

    private fun dfs(g: Int, bound: Int, cameFrom: Int): Int {
        if (System.nanoTime() > deadline) { aborted = true; return FOUND }
        val f = g + heuristic()
        if (f > bound) return f
        if (solved()) return FOUND
        var min = Int.MAX_VALUE
        val r = blank / cols
        val c = blank % cols
        val nbs = intArrayOf(
            if (r > 0) blank - cols else -1,
            if (r < rows - 1) blank + cols else -1,
            if (c > 0) blank - 1 else -1,
            if (c < cols - 1) blank + 1 else -1,
        )
        for (nb in nbs) {
            if (nb < 0 || !allowed[nb] || nb == cameFrom) continue
            val from = blank
            apply(nb)
            val t = dfs(g + 1, bound, from)
            if (aborted) return FOUND
            if (t == FOUND) return FOUND
            if (t < min) min = t
            undo(nb, from)
        }
        return min
    }

    private fun apply(neighbor: Int) {
        val dir = SlidePuzzleSolver.directionBetween(neighbor, blank, cols)
        path.add(SlideMove(neighbor, blank, dir))
        tiles[blank] = tiles[neighbor]
        tiles[neighbor] = blankId
        blank = neighbor
    }

    private fun undo(neighbor: Int, from: Int) {
        path.removeAt(path.size - 1)
        tiles[neighbor] = tiles[from]
        tiles[from] = blankId
        blank = from
    }

    private fun solved(): Boolean {
        for (s in 0 until n) if (allowed[s] && tiles[s] != goal[s]) return false
        return true
    }

    private fun heuristic(): Int {
        var h = 0
        for (s in 0 until n) {
            if (!allowed[s]) continue
            val t = tiles[s]
            if (t == blankId) continue
            val g = goalPos[t]
            h += abs(s / cols - g / cols) + abs(s % cols - g % cols)
        }
        return h
    }
}
