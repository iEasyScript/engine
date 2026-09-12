package com.projectx.script.impl.trent.clue

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.quest.overlay.pulseAlpha
import com.projectx.script.api.varps
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors

private const val TOWERS_INTERFACE = 1934
private const val GRID_COMPONENT = 23
private const val SIZE = 5
private const val FIRST_HINT_VARBIT = 39747
private const val FIRST_CELL_VARBIT = 39675

/** Cell ops are Increment, 1..5, Clear - so setting height h is option h + 1. */
private const val HEIGHT_OPTION_BASE = 1

/**
 * Towers (skyscrapers), interface `trail17_skyscrapers`.
 *
 * The twenty edge clues and the current grid are both player varbits: clues in top / left / bottom /
 * right blocks of five from [FIRST_HINT_VARBIT], the grid row-major from [FIRST_CELL_VARBIT]. The cells
 * themselves are dynamic sub-components the client builds at runtime and never publishes through the
 * parent's component list, so their rects come from subdividing the grid component rather than from the
 * components - reading them through the component tree yields nothing at all.
 */
class TowersModule : ClueModule {

    override val name = "Towers"

    private var solutions: List<IntArray> = emptyList()
    private var entered = IntArray(SIZE * SIZE)
    private var clueSignature = ""

    override fun active() = isOnScreen(TOWERS_INTERFACE) && rectOf(TOWERS_INTERFACE, GRID_COMPONENT) != null

    override fun reset() {
        solutions = emptyList()
        entered = IntArray(SIZE * SIZE)
        clueSignature = ""
    }

    override fun update() {
        entered = IntArray(SIZE * SIZE) { varbit(FIRST_CELL_VARBIT + it) }
        val clues = readClues()
        if (clues.isEmpty()) return
        val signature = (clues.top + clues.left + clues.bottom + clues.right).joinToString()
        if (signature == clueSignature) return
        clueSignature = signature
        solutions = TowersSolver.solutions(clues)
        println("[ClueHelper] towers clues=$signature solutions=${solutions.size}")
    }

    /** Heights every solution agrees on; 0 where the clues genuinely leave a cell ambiguous. */
    private fun settled(): IntArray {
        val first = solutions.firstOrNull() ?: return IntArray(SIZE * SIZE)
        return IntArray(SIZE * SIZE) { cell -> if (solutions.all { it[cell] == first[cell] }) first[cell] else 0 }
    }

    private fun alternatives(cell: Int) = solutions.map { it[cell] }.distinct().sorted()

    /**
     * Fills from the first solution rather than only the settled cells: when two grids both satisfy the
     * clues either is a legal board, and half a board helps nobody.
     */
    override fun nextAction(): ClueAction? {
        val grid = solutions.firstOrNull() ?: return null
        val cell = grid.indices.firstOrNull { entered[it] != grid[it] } ?: return null
        return ClueAction("tower cell $cell -> ${grid[cell]}") {
            IFSlot(TOWERS_INTERFACE, GRID_COMPONENT, cell).click(grid[cell] + HEIGHT_OPTION_BASE)
        }
    }

    override fun render(scope: BackgroundDrawListScope) {
        val grid = rectOf(TOWERS_INTERFACE, GRID_COMPONENT) ?: return
        if (solutions.isEmpty()) {
            scope.panel(grid, listOf(name, "No grid satisfies these clues"), ImGuiColors.RED or (255 shl 24))
            return
        }
        val settled = settled()
        with(scope) {
            for (cell in 0 until SIZE * SIZE) {
                drawCell(cellOf(grid, SIZE, SIZE, cell), settled[cell], entered.getOrElse(cell) { 0 }, cell)
            }
            panel(grid, lines(settled), accent(settled))
        }
    }

    private fun BackgroundDrawListScope.drawCell(cell: ScreenRect, solved: Int, current: Int, index: Int) {
        if (solved == 0) {
            val color = ImGuiColors.YELLOW or (220 shl 24)
            outline(cell, color, thickness = 2f, pad = -2f)
            centredText(cell, alternatives(index).joinToString("/"), color)
            return
        }
        when {
            current == solved -> outline(cell, ImGuiColors.GREEN or (255 shl 24), thickness = 2f, pad = -2f)
            current != 0 -> {
                val color = ImGuiColors.RED or (255 shl 24)
                outline(cell, color, thickness = 2f, pad = -2f)
                centredText(cell, solved.toString(), color)
            }
            else -> {
                val color = pulseAlpha(ImGuiColors.CYAN, minAlpha = 200)
                outline(cell, color, thickness = 2f, pad = -2f)
                centredText(cell, solved.toString(), color)
            }
        }
    }

    private fun readClues(): TowerClues {
        val hints = IntArray(20) { varbit(FIRST_HINT_VARBIT + it) }
        return TowerClues(
            top = hints.copyOfRange(0, 5),
            left = hints.copyOfRange(5, 10),
            bottom = hints.copyOfRange(10, 15),
            right = hints.copyOfRange(15, 20),
        )
    }

    private fun varbit(id: Int) = runCatching { varps.getVarBit(id) }.getOrDefault(0)

    private fun lines(settled: IntArray) = buildList {
        add(name)
        if (solutions.size > 1) add("${solutions.size} grids fit these clues")
        val target = solutions.first()
        val left = target.indices.count { entered[it] != target[it] }
        if (left == 0) add("Solved - press Check") else add("$left cells to place")
        for (row in 0 until SIZE) {
            add((0 until SIZE).joinToString(" ") { col ->
                val cell = row * SIZE + col
                if (settled[cell] != 0) settled[cell].toString() else alternatives(cell).joinToString("/")
            })
        }
    }

    private fun accent(settled: IntArray) = when {
        solutions.first().indices.any { entered[it] != solutions.first()[it] } -> ImGuiColors.CYAN
        solutions.size > 1 -> ImGuiColors.YELLOW
        else -> ImGuiColors.GREEN
    } or (255 shl 24)
}
