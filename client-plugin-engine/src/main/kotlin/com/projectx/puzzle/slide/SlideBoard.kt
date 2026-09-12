package com.projectx.puzzle.slide

/**
 * A sliding-tile puzzle position. [tiles] holds the piece id at each slot in row-major order
 * (`slot = row * cols + col`); [blankId] is the piece id that represents the hole.
 *
 * Engine-independent and immutable — every transformation returns a new board.
 */
class SlideBoard(
    val rows: Int,
    val cols: Int,
    val tiles: IntArray,
    val blankId: Int,
) {
    init {
        require(rows > 0 && cols > 0) { "rows/cols must be positive" }
        require(tiles.size == rows * cols) { "tiles size ${tiles.size} != ${rows * cols}" }
    }

    val size: Int get() = tiles.size

    fun rowOf(slot: Int) = slot / cols
    fun colOf(slot: Int) = slot % cols
    fun slotOf(row: Int, col: Int) = row * cols + col

    fun blankSlot(): Int = tiles.indexOf(blankId).also {
        require(it >= 0) { "blank piece $blankId not present on board" }
    }

    fun isSolved(goal: IntArray): Boolean = tiles.contentEquals(goal)

    /** Apply a single move and return the resulting board without mutating this one. */
    fun applied(move: SlideMove): SlideBoard {
        val next = tiles.copyOf()
        next[move.blankSlot] = tiles[move.tileSlot]
        next[move.tileSlot] = blankId
        return SlideBoard(rows, cols, next, blankId)
    }

    /** Order-sensitive content key, cheap enough to recompute every tick for change detection. */
    fun signature(): Long {
        var h = 1125899906842597L
        for (t in tiles) h = h * 1099511628211L + t
        return h
    }

    companion object {
        /** Goal where piece `i` belongs at slot `i` (the standard solved arrangement). */
        fun identityGoal(size: Int): IntArray = IntArray(size) { it }
    }
}
