package com.projectx.quest.solver

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.puzzle.slide.SlideBoard
import com.projectx.puzzle.slide.SlidePuzzleSolver
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep

/**
 * Drives the Dead and Buried map-table slide puzzle. The puzzle is 24 "Map part" NPCs
 * (piece `n` = type id [FIRST_PIECE_NPC] + n - 1) arranged 5x5 around one empty slot; clicking a
 * Map part adjacent to the empty slot slides it in. The solved map reads the pieces in ascending
 * order left-to-right, north-to-south, so piece `p` belongs at slot `p - 1` (row-major, north = row
 * 0, west = column 0) and the empty slot sits at the south-east corner. That makes the goal the
 * identity arrangement.
 *
 * Solved with the shared [SlidePuzzleSolver]; the next Map part to click is highlighted as an NPC
 * ([QuestAction.ModelHighlight]). Advisory only.
 */
object DeadAndBuriedMapPuzzleSolver : QuestStepSolver {
    override val id = "dead-and-buried.map-puzzle"

    private const val ROWS = 5
    private const val COLS = 5
    private const val FIRST_PIECE_NPC = 30124
    private const val PIECE_COUNT = 24
    private const val BLANK = PIECE_COUNT

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        val current = readCurrentFromPieces() ?: return QuestStepSolver.Result()
        val goal = SlideBoard.identityGoal(ROWS * COLS)

        val moves = SlidePuzzleSolver.solve(SlideBoard(ROWS, COLS, current, BLANK), goal) ?: return QuestStepSolver.Result()
        if (moves.isEmpty()) return QuestStepSolver.Result(solved = true)

        val piece = current[moves[0].tileSlot]
        if (piece !in 0 until PIECE_COUNT) return QuestStepSolver.Result()
        val npcType = FIRST_PIECE_NPC + piece
        return QuestStepSolver.Result(
            overlayActions = listOf(
                QuestAction.ModelHighlight(
                    kind = "npc",
                    typeId = npcType,
                    modelIds = emptyList(),
                    candidateNpcTypeIds = listOf(npcType),
                    displayName = "Slide piece ${piece + 1}",
                ),
            ),
        )
    }

    /** The live arrangement of the 24 Map part NPCs; the one empty cell is the blank. */
    private fun readCurrentFromPieces(): IntArray? {
        val pieces = readPieces()
        if (pieces.size != PIECE_COUNT) return null
        val xs = pieces.map { it.x }.distinct().sorted()
        val ys = pieces.map { it.y }.distinct().sorted()
        if (xs.size != COLS || ys.size != ROWS) return null

        val board = IntArray(ROWS * COLS) { BLANK }
        for (p in pieces) {
            val col = xs.indexOf(p.x) // west = column 0
            val row = ROWS - 1 - ys.indexOf(p.y) // highest y (north) = row 0
            board[row * COLS + col] = p.piece
        }
        return board.takeIf { isPermutation(it) }
    }

    private data class Piece(val piece: Int, val x: Int, val y: Int)

    private fun readPieces(): List<Piece> {
        val out = ArrayList<Piece>(PIECE_COUNT)
        val seen = HashSet<Int>()
        val mgr = runCatching { Bootstrap.client.npcManager }.getOrNull() ?: return out
        for (hashCode in runCatching { mgr.indices }.getOrNull() ?: return out) {
            if (hashCode <= 0) continue
            val ptr = runCatching { mgr[hashCode] }.getOrNull() ?: continue
            if (ptr.address() == 0L) continue
            val npc = NPC(ptr)
            if (!runCatching { npc.exists() }.getOrDefault(false)) continue
            val typeId = runCatching { if (npc.typeId == -1) npc.id else npc.typeId }.getOrNull() ?: continue
            val piece = typeId - FIRST_PIECE_NPC
            if (piece !in 0 until PIECE_COUNT) continue
            if (!seen.add(piece)) continue
            val tile = runCatching { npc.tile }.getOrNull() ?: continue
            out += Piece(piece, tile.x, tile.y)
        }
        return out
    }

    private fun isPermutation(tiles: IntArray): Boolean {
        val seen = BooleanArray(ROWS * COLS)
        for (t in tiles) {
            if (t !in seen.indices || seen[t]) return false
            seen[t] = true
        }
        return true
    }
}
