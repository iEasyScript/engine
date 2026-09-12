package com.projectx.script.impl.trent.clue

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.math.Vector2f
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.puzzle.slide.Direction
import com.projectx.puzzle.slide.InterfaceSlidePuzzleSource
import com.projectx.puzzle.slide.SlideMove
import com.projectx.puzzle.slide.SlidePuzzleSolver
import com.projectx.quest.overlay.arrow
import com.projectx.quest.overlay.pulseAlpha
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors

private const val LOOKAHEAD = 4

/**
 * The Treasure Trails puzzle box (interface 1931): a 5x5 slide puzzle whose tile arrangement lives in
 * client varbits 39405..39429. The blank piece is 24 - the default of struct param 5694, which the
 * puzzle structs do not override.
 */
class SlidePuzzleModule : ClueModule {

    override val name = "Puzzle box"

    private val source = InterfaceSlidePuzzleSource(
        id = "clue-puzzle-box",
        interfaceId = 1931,
        gridComponentId = 18,
        rows = 5,
        cols = 5,
        firstTileVarbit = 39405,
        blankTileId = 24,
    )

    private var moves: List<SlideMove> = emptyList()
    private var solvable = true
    private var boardSignature = 0L

    override fun active() = source.isActive()

    override fun reset() {
        moves = emptyList()
        solvable = true
        boardSignature = 0L
    }

    override fun update() {
        val board = source.readBoard() ?: return
        val signature = board.signature()
        if (signature == boardSignature) return
        boardSignature = signature
        val solution = SlidePuzzleSolver.solve(board, source.goal())
        solvable = solution != null
        moves = solution.orEmpty()
    }

    override fun nextAction(): ClueAction? {
        val move = moves.firstOrNull() ?: return null
        return ClueAction("slide tile ${move.tileSlot} ${move.direction}") {
            IFSlot(source.interfaceId, source.gridComponentId, move.tileSlot).click()
        }
    }

    override fun render(scope: BackgroundDrawListScope) {
        val grid = rectOf(source.interfaceId, source.gridComponentId) ?: return
        with(scope) {
            moves.take(LOOKAHEAD).forEachIndexed { index, move ->
                drawMove(cellOf(grid, source.rows, source.cols, move.tileSlot), move.direction, index + 1)
            }
            panel(grid, lines(), accent())
        }
    }

    private fun BackgroundDrawListScope.drawMove(cell: ScreenRect, direction: Direction, ordinal: Int) {
        val primary = ordinal == 1
        val color = if (primary) {
            pulseAlpha(ImGuiColors.CYAN, minAlpha = 220, maxAlpha = 255)
        } else {
            (ImGuiColors.CYAN and 0x00FFFFFF) or ((170 - (ordinal - 2) * 30).coerceIn(90, 170) shl 24)
        }
        outline(cell, color, thickness = if (primary) 3f else 1.5f, pad = -2f)

        val length = minOf(cell.width, cell.height) * 0.62f
        val dx = when (direction) { Direction.LEFT -> -1f; Direction.RIGHT -> 1f; else -> 0f }
        val dy = when (direction) { Direction.UP -> -1f; Direction.DOWN -> 1f; else -> 0f }
        val centre = Vector2f(cell.x + cell.width / 2f, cell.y + cell.height / 2f)
        arrow(
            Vector2f(centre.x - dx * length / 2f, centre.y - dy * length / 2f),
            Vector2f(centre.x + dx * length / 2f, centre.y + dy * length / 2f),
            color,
            thickness = if (primary) 5f else 3f,
            headSize = minOf(cell.width, cell.height) * 0.34f,
        )
        text(Vector2f(cell.x + 4f, cell.y + 1f), color, ordinal.toString())
    }

    private fun lines() = buildList {
        add(name)
        when {
            !solvable -> add("Board is unsolvable")
            moves.isEmpty() -> add("Solved")
            else -> {
                add("${moves.size} moves left")
                moves.take(LOOKAHEAD).forEachIndexed { index, move -> add("${index + 1}. ${move.direction}") }
            }
        }
    }

    private fun accent() = when {
        !solvable -> ImGuiColors.RED
        moves.isEmpty() -> ImGuiColors.GREEN
        else -> ImGuiColors.CYAN
    } or (255 shl 24)
}
