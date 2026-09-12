package com.projectx.quest.solver

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.puzzle.slide.SlideBoard
import com.projectx.puzzle.slide.SlidePuzzleSolver
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.script.api.interfaces
import com.projectx.script.api.localPlayer
import kotlin.math.abs

/**
 * Drives the Desperate Times slide puzzle. The puzzle itself is the eight "Sliding block" NPCs arranged
 * 3x3 around one empty slot in the world — you click a block adjacent to the empty slot and it slides in.
 * The "WORD GRID" interface (115) is just the solution to copy (north / +Y is the top of the grid).
 *
 *  - Goal  ← interface 115's nine cells (component `21 + index*8`, row-major; empty text = blank). Cached
 *    against the room because the interface is closed while the player actually slides the blocks.
 *  - Current ← the eight block NPCs clustered into a 3x3 by tile (3 distinct x = cols west→east, 3 distinct
 *    y = rows with north/+Y on top); the missing cell is the empty slot.
 *
 * Solved with the shared [SlidePuzzleSolver]; the next block to click is highlighted as an NPC
 * ([QuestAction.ModelHighlight]). Reads NPCs on the main-logic tick, so the in-scene blocks resolve.
 * Advisory only.
 */
object DesperateTimesSlidePuzzleSolver : QuestStepSolver {
    override val id = "desperate-times.slide-puzzle"

    private const val IFACE = 115
    private const val CELL0 = 21
    private const val CELL_STEP = 8
    private const val ROWS = 3
    private const val COLS = 3
    private const val BLANK = 8
    private const val ROOM_RADIUS = 16

    private val WORD_TO_SYMBOL = mapOf(
        "Diamond" to 0, "Two Dots" to 1, "Square" to 2, "House" to 3,
        "Cross" to 4, "Circle" to 5, "Arches" to 6, "Triangle" to 7,
    )
    private val NPC_TYPE_BY_SYMBOL = intArrayOf(26309, 26307, 17044, 17046, 26310, 17043, 26308, 17045)
    private val SYMBOL_BY_NPC_TYPE = NPC_TYPE_BY_SYMBOL.withIndex().associate { (sym, npc) -> npc to sym }
    private val SYMBOL_NAMES = arrayOf("Diamond", "Two Dots", "Square", "House", "Cross", "Circle", "Arches", "Triangle")

    // Goal arrangement cached from interface 115, scoped to the room it was read in.
    private var goal: IntArray? = null
    private var goalAnchorX = 0
    private var goalAnchorY = 0
    private var goalAnchorPlane = Int.MIN_VALUE

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        cacheGoalIfInterfaceOpen()
        val goal = goalForThisRoom() ?: return QuestStepSolver.Result()
        val current = readCurrentFromBlocks() ?: return QuestStepSolver.Result()

        val moves = SlidePuzzleSolver.solve(SlideBoard(ROWS, COLS, current, BLANK), goal) ?: return QuestStepSolver.Result()
        if (moves.isEmpty()) return QuestStepSolver.Result(solved = true) // blocks already match the solution

        val symbol = current[moves[0].tileSlot]
        if (symbol !in NPC_TYPE_BY_SYMBOL.indices) return QuestStepSolver.Result()
        val npcType = NPC_TYPE_BY_SYMBOL[symbol]
        return QuestStepSolver.Result(
            overlayActions = listOf(
                QuestAction.ModelHighlight(
                    kind = "npc",
                    typeId = npcType,
                    modelIds = emptyList(),
                    candidateNpcTypeIds = listOf(npcType),
                    displayName = "Slide ${SYMBOL_NAMES[symbol]}",
                ),
            ),
        )
    }

    private fun cacheGoalIfInterfaceOpen() {
        if (!isOpen()) return
        val arrangement = readInterfaceGoal() ?: return
        val tile = runCatching { localPlayer.tile }.getOrNull() ?: return
        goal = arrangement
        goalAnchorX = tile.x
        goalAnchorY = tile.y
        goalAnchorPlane = tile.plane
    }

    private fun goalForThisRoom(): IntArray? {
        val g = goal ?: return null
        val tile = runCatching { localPlayer.tile }.getOrNull() ?: return null
        if (tile.plane != goalAnchorPlane) return null
        if (abs(tile.x - goalAnchorX) > ROOM_RADIUS || abs(tile.y - goalAnchorY) > ROOM_RADIUS) return null
        return g
    }

    /** The solution shown on interface 115; null unless it's a clean permutation with one blank. */
    private fun readInterfaceGoal(): IntArray? {
        val tiles = IntArray(ROWS * COLS)
        for (i in tiles.indices) {
            val text = runCatching { interfaces.getComponent(IFACE, CELL0 + i * CELL_STEP)?.text }.getOrNull()?.trim()
            tiles[i] = if (text.isNullOrEmpty()) BLANK else (WORD_TO_SYMBOL[text] ?: return null)
        }
        return tiles.takeIf { isPermutation(it) }
    }

    /** The live block arrangement from the eight Sliding block NPCs; the empty cell is the blank. */
    private fun readCurrentFromBlocks(): IntArray? {
        val blocks = readBlocks()
        if (blocks.size != 8) return null
        val xs = blocks.map { it.x }.distinct().sorted()
        val ys = blocks.map { it.y }.distinct().sorted()
        if (xs.size != COLS || ys.size != ROWS) return null

        val board = IntArray(ROWS * COLS) { BLANK }
        for (b in blocks) {
            val col = xs.indexOf(b.x)
            val row = ROWS - 1 - ys.indexOf(b.y) // highest y (north) = row 0
            board[row * COLS + col] = b.symbol
        }
        return board.takeIf { isPermutation(it) }
    }

    private data class Block(val symbol: Int, val x: Int, val y: Int)

    private fun readBlocks(): List<Block> {
        val out = ArrayList<Block>(8)
        val seen = HashSet<Int>()
        val mgr = runCatching { Bootstrap.client.npcManager }.getOrNull() ?: return out
        for (hashCode in runCatching { mgr.indices }.getOrNull() ?: return out) {
            if (hashCode <= 0) continue
            val ptr = runCatching { mgr[hashCode] }.getOrNull() ?: continue
            if (ptr.address() == 0L) continue
            val npc = NPC(ptr)
            if (!runCatching { npc.exists() }.getOrDefault(false)) continue
            val typeId = runCatching { if (npc.typeId == -1) npc.id else npc.typeId }.getOrNull() ?: continue
            val symbol = SYMBOL_BY_NPC_TYPE[typeId] ?: continue
            if (!seen.add(symbol)) continue
            val tile = runCatching { npc.tile }.getOrNull() ?: continue
            out += Block(symbol, tile.x, tile.y)
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

    private fun isOpen() = runCatching { interfaces.isOpen(IFACE) }.getOrDefault(false)

    /**
     * True when the WORD GRID (115) is open and shows a complete solution — exactly eight known symbol
     * words and one blank. Used by the combination-lock solver to auto-advance to this step once the
     * full solution is on screen.
     */
    fun isSolutionReady(): Boolean = isOpen() && readInterfaceGoal() != null
}
