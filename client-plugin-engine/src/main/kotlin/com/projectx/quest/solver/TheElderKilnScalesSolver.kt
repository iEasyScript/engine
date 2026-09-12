package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.script.api.interfaces

/**
 * Solver for the "ancient scales" puzzle in *The Elder Kiln* (the room-three
 * scales). Each scale shows an equation of four TzHaar numerals joined by three
 * operators; the player must place `resulting-sum` TokKul on the scale.
 *
 * The client draws each numeral/operator as the layer's own **text** (rendered in
 * the TzHaar font), set by IF_SETTEXT - not a graphic, model, or varbit-driven
 * lookup. So the equation is read straight off interface [SCALES] component text.
 *
 * TzHaar numerals are **base-12** (this is the wiki's off-by-2 gotcha: two-digit
 * numbers are NOT base-10). Each glyph is a letter whose value is its alphabet
 * position (`a`=1 ... `l`=12); a single glyph is that value (1–12), and space-
 * separated glyphs are base-12 digits - `"a f"` = 1×12 + 6 = 18 (a common mistake
 * is reading it as decimal 16). Operators: `Xo` = add, `Zi` = subtract (Journal of
 * Perjour's key). Only add/subtract occur, so the equation evaluates left-to-right.
 */
object TheElderKilnScalesSolver : QuestStepSolver {
    override val id: String = "the-elder-kiln.ancient-scales"

    private const val SCALES = 895
    private val NUMBER_COMPONENTS = 1..4
    private val SIGN_COMPONENTS = 5..7

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        if (!interfaces.isOpen(SCALES)) return QuestStepSolver.Result(overlayActions = reference())
        val equation = readEquation() ?: return QuestStepSolver.Result(overlayActions = reference())
        return QuestStepSolver.Result(
            overlayActions = listOf(
                QuestAction.TextHint("Scale: ${equation.pretty} = ${equation.sum}"),
                QuestAction.TextHint("Place ${equation.sum} TokKul on the scale."),
            )
        )
    }

    private data class Equation(val numbers: List<Int>, val addition: List<Boolean>) {
        val sum: Int = numbers.foldIndexed(0) { i, acc, n -> if (i == 0 || addition[i - 1]) acc + n else acc - n }
        val pretty: String = buildString {
            append(numbers[0])
            for (i in 1 until numbers.size) append(if (addition[i - 1]) " + ${numbers[i]}" else " - ${numbers[i]}")
        }
    }

    private fun readEquation(): Equation? {
        val numbers = NUMBER_COMPONENTS.map { numeral(text(it) ?: return null) ?: return null }
        val addition = SIGN_COMPONENTS.map {
            when (text(it)?.trim()?.lowercase()) {
                "xo" -> true
                "zi" -> false
                else -> return null
            }
        }
        return Equation(numbers, addition)
    }

    private fun text(component: Int): String? = interfaces.getComponent(SCALES, component)?.text?.takeIf { it.isNotBlank() }

    /**
     * Decode a TzHaar numeral. Each whitespace-separated glyph is a letter whose
     * value is its alphabet position (a=1..l=12), combined as base-12 digits - a
     * single glyph is that value 1..12, "a f" = 1×12 + 6 = 18.
     */
    private fun numeral(glyph: String): Int? {
        var value = 0
        for (token in glyph.trim().split(WHITESPACE)) {
            if (token.length != 1) return null
            val digit = token[0].lowercaseChar() - 'a' + 1
            if (digit !in 1..12) return null
            value = value * 12 + digit
        }
        return value.takeIf { it > 0 }
    }

    private val WHITESPACE = Regex("\\s+")

    private fun reference(): List<QuestAction> = listOf(
        QuestAction.TextHint("Open a scale - I'll read its equation and give the exact TokKul to place."),
        QuestAction.TextHint("TzHaar numerals are base-12: a..l = 1..12, \"a f\" = 1×12+6 = 18 (not 16!). Xo = add, Zi = subtract."),
    )
}
