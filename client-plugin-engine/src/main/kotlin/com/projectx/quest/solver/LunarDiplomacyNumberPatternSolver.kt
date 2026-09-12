package com.projectx.quest.solver

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.data.WorldLocation
import com.projectx.quest.runtime.RecentChat
import com.projectx.script.api.getAllObjectsWithinRange
import world.gregs.voidps.type.Tile

/**
 * Lunar Diplomacy "Number sequence" puzzle (Ethereal Numerator). The Numerator shows the start of a
 * number pattern in chat and the player must click the numbered tiles (`lunar_dream_number_0..9`) to
 * continue it, four rounds in a row.
 *
 * Each tick the solver reads the newest number sequence from chat, matches it as a prefix of a known
 * pattern to derive the continuation, and highlights each remaining number tile with the click order
 * spelled out in a text hint. As the chat prefix grows (a correct click echoes, or the next round
 * begins) the highlighted set recomputes. Highlight-only; never clicks. Advancement is left to the
 * step's existing "got the hang of this" dialogue postcondition.
 */
object LunarDiplomacyNumberPatternSolver : QuestStepSolver {
    override val id: String = "lunar-diplomacy.number-pattern"

    private const val SCAN_RANGE = 15
    private const val NUMBER_LOC_BASE = 16619  // lunar_dream_number_0; digit d -> base + d

    /** Complete patterns from the quest wiki; a chat prefix identifies which one and its continuation. */
    private val PATTERNS: List<List<Int>> = listOf(
        listOf(0, 1, 3, 4, 6, 7),
        listOf(1, 1, 1, 2, 1, 3, 1, 4, 1, 5),
        listOf(1, 1, 2, 2, 3, 3, 4),
        listOf(1, 1, 2, 3, 1, 1, 4, 5, 1),
        listOf(1, 2, 3, 4, 5),
        listOf(1, 3, 5, 7, 9),
        listOf(1, 4, 2, 5, 3, 6),
        listOf(1, 6, 2, 5, 3, 4),
        listOf(1, 9, 2, 8, 3, 7),
        listOf(2, 3, 5, 6, 8, 9),
        listOf(2, 6, 3, 7, 4, 8),
        listOf(3, 4, 2, 5, 1, 6),
        listOf(7, 3, 6, 2, 5, 1),
        listOf(8, 6, 4, 2, 0),
        listOf(9, 7, 5, 3, 1),
        listOf(9, 8, 7, 6, 5, 4),
    )

    private val SEQUENCE = Regex("(?:[0-9]\\s*,\\s*){2,}[0-9]")
    private val DIGIT = Regex("[0-9]")

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        val given = latestSequence() ?: return hint("Waiting for the Ethereal Numerator to show a pattern.")
        val remaining = continuationOf(given)
            ?: return hint("Pattern ${given.joinToString(", ")} - need more numbers to identify it.")
        if (remaining.isEmpty()) return hint("Pattern complete - wait for the next one.")

        val tiles = numberTiles()
        val order = remaining.joinToString(" -> ")
        val highlights = remaining.distinct().mapNotNull { d -> tiles[d]?.let { highlight(d, it) } }
        return QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint("Click in order: $order")) + highlights)
    }

    /** Newest chat message holding a number sequence that is a prefix of a known pattern. */
    private fun latestSequence(): List<Int>? =
        RecentChat.latestMatch(SEQUENCE)?.value
            ?.let { DIGIT.findAll(it).map { m -> m.value.toInt() }.toList() }
            ?.takeIf { it.size >= 3 }

    /** The numbers still to click, or null if [given] doesn't unambiguously match one pattern's tail. */
    private fun continuationOf(given: List<Int>): List<Int>? {
        val matches = PATTERNS.filter { it.size > given.size && it.subList(0, given.size) == given }
        if (matches.isEmpty()) return if (PATTERNS.any { it == given }) emptyList() else null
        val remainders = matches.map { it.drop(given.size) }.distinct()
        return remainders.singleOrNull()
    }

    private fun numberTiles(): Map<Int, Tile> {
        val out = HashMap<Int, Tile>(10)
        for (obj in runCatching { getAllObjectsWithinRange(SCAN_RANGE) }.getOrNull().orEmpty()) {
            val digit = obj.id - NUMBER_LOC_BASE
            if (digit in 0..9 && digit !in out) out[digit] = obj.tile
        }
        return out
    }

    private fun highlight(digit: Int, tile: Tile) = QuestAction.ModelHighlight(
        kind = "object",
        typeId = NUMBER_LOC_BASE + digit,
        modelIds = emptyList(),
        displayName = "Click $digit",
        atLocation = WorldLocation(tile.x.toDouble(), 0.0, tile.y.toDouble()),
    )

    private fun hint(text: String) = QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint(text)))
}
