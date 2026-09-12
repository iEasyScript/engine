package com.projectx.puzzle.slide

/**
 * Where a slide puzzle's live state comes from. Decouples the [SlidePuzzleSolver] and overlay from any
 * specific interface or world layout — the interface-1931 puzzle box is one implementation
 * ([InterfaceSlidePuzzleSource]); a future scenery-based puzzle (objects slid around a room) is another.
 */
interface SlidePuzzleSource {
    val id: String
    val rows: Int
    val cols: Int

    /** True when this puzzle is present and should be solved/overlaid right now. */
    fun isActive(): Boolean

    /** Current arrangement, or null if it can't be read this tick. */
    fun readBoard(): SlideBoard?

    /** Solved arrangement: goal[slot] = piece id that belongs there. Identity by default. */
    fun goal(): IntArray = SlideBoard.identityGoal(rows * cols)
}
