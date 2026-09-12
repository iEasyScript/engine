package com.projectx.script.impl.trent.clue

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.math.Vector2f
import com.projectx.game.nxt.interfaces.ScreenRect
import com.projectx.puzzle.knot.CelticKnotLayouts
import com.projectx.puzzle.knot.CelticKnotSolver
import com.projectx.puzzle.knot.EMPTY_RUNE
import com.projectx.puzzle.knot.KnotLayout
import com.projectx.puzzle.knot.KnotRotation
import com.projectx.quest.overlay.arrow
import com.projectx.quest.overlay.pulseAlpha
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors

private class KnotSolution(
    val layout: KnotLayout,
    val moves: List<KnotRotation>,
    val alignedCrossings: List<Boolean>,
    val solvable: Boolean,
) {
    val aligned get() = alignedCrossings.count { it }
    val solved get() = solvable && moves.isEmpty()
}

/**
 * Celtic knots: every ring is a closed loop of runes that can only be rotated whole, so the solution is
 * one offset per ring and the overlay is a click count on each rotation button plus a live per-crossing
 * match indicator.
 */
class CelticKnotModule : ClueModule {

    override val name = "Celtic knot"

    private var interfaceId = -1
    private var solution: KnotSolution? = null
    private var lastRunes: List<IntArray> = emptyList()

    override fun active(): Boolean {
        interfaceId = CelticKnotLayouts.interfaceIds.firstOrNull { isOpen(it) } ?: -1
        return interfaceId >= 0
    }

    override fun reset() {
        interfaceId = -1
        solution = null
        lastRunes = emptyList()
    }

    override fun update() {
        val id = interfaceId
        val layout = CelticKnotLayouts.forInterface(id) { rectOf(id, it) } ?: return
        val runes = CelticKnotLayouts.readRunes(layout) { graphicOf(id, it) }
        if (runes.any { ring -> ring.all { it == EMPTY_RUNE } }) return
        if (lastRunes.size == runes.size && runes.indices.all { lastRunes[it].contentEquals(runes[it]) }) return
        lastRunes = runes

        val settled = IntArray(layout.tracks.size)
        val moves = CelticKnotSolver.solve(layout, runes)
        solution = KnotSolution(
            layout,
            moves.orEmpty(),
            layout.crossings.map { CelticKnotSolver.aligned(runes, it, settled) },
            solvable = moves != null,
        )
    }

    override fun nextAction(): ClueAction? {
        val state = solution ?: return null
        val move = state.moves.firstOrNull() ?: return null
        val id = interfaceId
        return ClueAction("knot track ${move.track.index} ${label(move)}") {
            IFSlot(id, move.buttonComponent).click()
        }
    }

    override fun render(scope: BackgroundDrawListScope) {
        val state = solution ?: return
        val id = interfaceId
        if (id < 0 || !isOpen(id)) return
        with(scope) {
            state.layout.crossings.forEachIndexed { index, crossing ->
                val tile = rectOf(id, crossing.tileComponent) ?: return@forEachIndexed
                drawCrossing(tile, state.alignedCrossings.getOrElse(index) { false })
            }
            state.moves.forEach { move ->
                val button = rectOf(id, move.buttonComponent) ?: return@forEach
                drawButton(button, label(move))
            }
            rectOf(id, state.layout.windowComponent)?.let { panel(it, lines(state), accent(state)) }
        }
    }

    private fun BackgroundDrawListScope.drawCrossing(tile: ScreenRect, aligned: Boolean) {
        val centre = Vector2f(tile.x + tile.width / 2f, tile.y + tile.height / 2f)
        val radius = minOf(tile.width, tile.height) * 0.42f
        if (aligned) {
            circle(centre, radius, ImGuiColors.GREEN or (255 shl 24), thickness = 2f)
            circle(centre, radius * 0.6f, (ImGuiColors.GREEN and 0x00FFFFFF) or (90 shl 24), thickness = 6f)
        } else {
            circle(centre, radius, pulseAlpha(ImGuiColors.YELLOW, minAlpha = 140), thickness = 2.5f)
        }
    }

    private fun BackgroundDrawListScope.drawButton(button: ScreenRect, text: String) {
        val color = pulseAlpha(ImGuiColors.CYAN, minAlpha = 220, maxAlpha = 255)
        outline(button, color, thickness = 3f, pad = 3f)
        val centre = Vector2f(button.x + button.width / 2f, button.y + button.height / 2f)
        val span = minOf(button.width, button.height) * 0.34f
        val direction = if (text.endsWith("CCW")) -1f else 1f
        arrow(
            Vector2f(centre.x - span * direction, centre.y),
            Vector2f(centre.x + span * direction, centre.y),
            color,
            thickness = 4f,
            headSize = span,
        )
        label(Vector2f(centre.x, button.y + button.height + 5f), text, color)
    }

    private fun lines(state: KnotSolution) = buildList {
        add(name)
        add("Crossings ${state.aligned}/${state.alignedCrossings.size}")
        when {
            !state.solvable -> add("No rotation aligns this knot")
            state.solved -> add("Solved - every crossing matches")
            else -> state.moves.forEach { add("Track ${it.track.index}: ${label(it)}") }
        }
    }

    private fun accent(state: KnotSolution) = when {
        !state.solvable -> ImGuiColors.RED
        state.solved -> ImGuiColors.GREEN
        else -> ImGuiColors.CYAN
    } or (255 shl 24)

    private fun label(move: KnotRotation) = "${move.steps}x ${if (move.clockwise) "CW" else "CCW"}"
}
