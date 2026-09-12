package com.projectx.puzzle.slide

import com.projectx.script.api.interfaces
import com.projectx.script.api.varcs

/**
 * A slide puzzle backed by an interface whose tile arrangement lives in a contiguous run of client
 * varbits — one per slot, in row-major order, holding the piece id at that slot.
 *
 * For the RS3 puzzle box (interface 1931): [gridComponentId] = 18 (the 5x5 tile grid), tiles are read
 * from client varbits 39405..39429, and the blank piece (the one drawn with no graphic) is 24 — the
 * default of struct param 5694, which the puzzle structs do not override.
 */
class InterfaceSlidePuzzleSource(
    override val id: String,
    val interfaceId: Int,
    val gridComponentId: Int,
    override val rows: Int,
    override val cols: Int,
    private val firstTileVarbit: Int,
    private val blankTileId: Int,
) : SlidePuzzleSource {

    override fun isActive(): Boolean = runCatching { interfaces.isOpen(interfaceId) }.getOrDefault(false)

    override fun readBoard(): SlideBoard? {
        val n = rows * cols
        val tiles = IntArray(n)
        val seen = BooleanArray(n)
        for (i in 0 until n) {
            val piece = runCatching { varcs.getVarBit(firstTileVarbit + i) }.getOrElse { return null }
            if (piece !in 0 until n || seen[piece]) return null
            seen[piece] = true
            tiles[i] = piece
        }
        if (blankTileId !in 0 until n) return null
        return SlideBoard(rows, cols, tiles, blankTileId)
    }
}
