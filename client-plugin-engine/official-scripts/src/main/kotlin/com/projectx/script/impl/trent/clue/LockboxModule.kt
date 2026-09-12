package com.projectx.script.impl.trent.clue

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.quest.overlay.pulseAlpha
import com.projectx.script.api.varps
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors

private const val LOCKBOX_INTERFACE = 1933
private const val SIZE = 5
private const val FIRST_CELL_VARBIT = 39624

/** Squares are components 1..25, one per cell, in row-major order. */
private const val FIRST_CELL_COMPONENT = 1

/** The modal window, so the read-out sits beside the box instead of across the squares. */
private const val WINDOW_COMPONENT = 26

/**
 * Lockboxes, interface `lightsout`.
 *
 * The board is player varbits from [FIRST_CELL_VARBIT], row-major, one style per square. Each square is
 * its own component with its own rect, so the counts are drawn straight onto the squares.
 *
 * Clicking a square advances it and its four orthogonal neighbours by one style - verified live: a
 * centre press moved exactly its plus, a corner press only its three in-bounds squares, every delta
 * being +1 mod 3. The overlay shows how many times to press each square.
 */
class LockboxModule : ClueModule {

    override val name = "Lockbox"

    private var board = IntArray(SIZE * SIZE)
    private var plan: LockboxPlan? = null
    private var signature = ""

    override fun active() = isOnScreen(LOCKBOX_INTERFACE) && cellRect(0) != null

    override fun reset() {
        board = IntArray(SIZE * SIZE)
        plan = null
        signature = ""
    }

    override fun update() {
        board = IntArray(SIZE * SIZE) { runCatching { varps.getVarBit(FIRST_CELL_VARBIT + it) }.getOrDefault(0) }
        val current = board.joinToString()
        if (current == signature) return
        signature = current
        plan = LockboxSolver.solve(board, SIZE)
        println("[ClueHelper] lockbox board=$current presses=${plan?.totalPresses ?: -1}")
    }

    override fun nextAction(): ClueAction? {
        val presses = plan?.presses ?: return null
        val square = presses.indices.firstOrNull { presses[it] > 0 } ?: return null
        return ClueAction("lockbox square $square") {
            IFSlot(LOCKBOX_INTERFACE, FIRST_CELL_COMPONENT + square).click()
        }
    }

    override fun render(scope: BackgroundDrawListScope) {
        val presses = plan?.presses
        with(scope) {
            for (square in 0 until SIZE * SIZE) {
                val rect = cellRect(square) ?: continue
                drawSquare(rect, presses?.getOrNull(square) ?: 0)
            }
            (rectOf(LOCKBOX_INTERFACE, WINDOW_COMPONENT) ?: cellRect(0))?.let { panel(it, lines(), accent()) }
        }
    }

    private fun BackgroundDrawListScope.drawSquare(rect: ScreenRect, presses: Int) {
        if (presses <= 0) {
            outline(rect, ImGuiColors.GREEN or (110 shl 24), thickness = 1.5f, pad = 1f)
            return
        }
        val color = pulseAlpha(ImGuiColors.CYAN, minAlpha = 210)
        outline(rect, color, thickness = 2.5f, pad = 1f)
        countBadge(rect, presses.toString(), color)
    }

    private fun cellRect(square: Int) = rectOf(LOCKBOX_INTERFACE, FIRST_CELL_COMPONENT + square)

    private fun lines() = buildList {
        add(name)
        val current = plan
        when {
            current == null -> add("No sequence solves this board")
            current.totalPresses == 0 -> add("Solved")
            else -> {
                add("${current.totalPresses} presses left")
                add("${current.presses.count { it > 0 }} squares to press")
            }
        }
    }

    private fun accent() = when {
        plan == null -> ImGuiColors.RED
        plan?.totalPresses == 0 -> ImGuiColors.GREEN
        else -> ImGuiColors.CYAN
    } or (255 shl 24)
}
