package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.script.api.interfaces
import com.projectx.script.api.varps

/**
 * Final tower manipulation for Elemental Workshop III, performed AFTER the lattice puzzle is parked
 * (tower raised) and a body bar has been made. The player returns to the lever interface and drops,
 * spins, then raises the tower with a short fixed sequence.
 *
 * Two start positions exist. The lattice helper leaves the tower in the **guide** position, so that
 * sequence is the default; the inverse only differs in the drop:
 *   guide:   2 (drop) · 4, B (spin) · C, 3 (rise)
 *   inverse: 4, 3 (drop) · 4, B (spin) · C, 3 (rise)
 *
 * The move counter is blank/unreliable here, so a move is detected by the gear state ([GEAR_VARP])
 * changing. Highlight-only; state touched only from the main-logic thread ([evaluate]).
 */
object ElementalWorkshopIIITowerSolver : QuestStepSolver {
    override val id: String = "elemental-workshop-iii.tower-final"

    private const val PUZZLE = 1689
    private const val GEAR_VARP = 2540

    private val LEVER_COMP = mapOf(
        "A" to 15, "B" to 14, "C" to 13, "D" to 12, "E" to 11, "F" to 10,
        "1" to 9, "2" to 8, "3" to 7, "4" to 6, "5" to 5, "6" to 4, "7" to 3,
    )

    private val GUIDE = listOf("2", "4", "B", "C", "3")
    private val ACTION = listOf("the tower drops", "the tower spins", "", "the tower rises", "")

    private var step = 0
    private var lastGear: Int? = null

    override fun evaluate(quest: Quest, step0: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        if (!isOpen()) {
            step = 0; lastGear = null
            return hint("Return to the lattice room and open the lever interface to drop, spin and raise the tower.")
        }
        gearValue()?.let { gear ->
            if (lastGear == null) lastGear = gear
            else if (gear != lastGear) { step++; lastGear = gear }
        }

        val seq = GUIDE
        if (step >= seq.size) return solved("Tower dropped, spun and raised - head up to make the second body bar.")
        val label = seq[step]
        val comp = LEVER_COMP[label] ?: return hint("Lever '$label' is not mapped.")
        val note = ACTION.getOrNull(step)?.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""
        return result(
            "Tower step ${step + 1}/${seq.size}: pull lever $label$note. (Inverse position? the drop is 4 then 3.)",
            comp, "Pull $label",
        )
    }

    private fun gearValue(): Int? = try { varps.getVar(GEAR_VARP) } catch (_: Throwable) { null }
    private fun isOpen(): Boolean = try { interfaces.isOpen(PUZZLE) } catch (_: Throwable) { false }

    private fun result(text: String, comp: Int, label: String) = QuestStepSolver.Result(
        overlayActions = listOf(QuestAction.TextHint(text), QuestAction.InterfaceComponentHighlight(PUZZLE, comp, label = label)),
    )

    private fun hint(text: String) = QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint(text)))
    private fun solved(text: String) = QuestStepSolver.Result(solved = true, overlayActions = listOf(QuestAction.TextHint(text)))
}
