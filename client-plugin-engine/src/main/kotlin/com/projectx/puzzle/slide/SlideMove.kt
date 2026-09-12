package com.projectx.puzzle.slide

/**
 * A single-tile slide: the tile occupying [tileSlot] moves [direction] into the hole at [blankSlot].
 *
 * [tileSlot] is always orthogonally adjacent to [blankSlot] — it is the cell the player clicks, and
 * [direction] is the way the arrow on that cell points.
 */
data class SlideMove(val tileSlot: Int, val blankSlot: Int, val direction: Direction)
