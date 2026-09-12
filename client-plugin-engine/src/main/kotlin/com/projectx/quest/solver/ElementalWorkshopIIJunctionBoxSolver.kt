package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.script.api.interfaces
import com.projectx.script.api.varcs

/**
 * Solver for the junction-box pipe puzzle in *Elemental Workshop II* (step 25 —
 * "connect the pipes by clicking on a pair of pipe holes").
 *
 * Interface [JUNCTION_BOX] has six pipe holes; the solution is three fixed pairs,
 * each connected by clicking both holes:
 *   - top-left ([TL]=24)    + bottom-right ([BR]=23)
 *   - top-middle ([TM]=25)  + top-right ([TR]=20)
 *   - bottom-left ([BL]=21) + bottom-middle ([BM]=22)
 *
 * Per-hole selection state lives in client vars (CS2 scripts 1318/1319 toggle
 * `varclient_162..167`): 1 = that hole is currently selected/lit, 0 = not. A
 * connection is two clicks — select a hole, then click its partner.
 *
 * The solver highlights the next button to press and self-corrects: when a hole is
 * selected it points at that hole's fixed partner (so any mis-selection is recovered
 * by completing or re-clicking it); when nothing is selected it lights the first hole
 * of all three pairs so the player can start any of them.
 *
 * NOTE: the connection/progress varbits (player 13703/13704) proved unreliable on a
 * fresh puzzle (varp 2746 decodes 13704=3 with nothing connected), so this relies on
 * the client-var selection state plus the fixed solution rather than a progress count.
 */
object ElementalWorkshopIIJunctionBoxSolver : QuestStepSolver {
    override val id: String = "elemental-workshop-ii.junction-box"

    private const val JUNCTION_BOX = 262

    private const val TL = 24
    private const val TM = 25
    private const val TR = 20
    private const val BL = 21
    private const val BM = 22
    private const val BR = 23

    /** Fixed solution: each hole's partner. */
    private val PARTNER = mapOf(TL to BR, BR to TL, TM to TR, TR to TM, BL to BM, BM to BL)

    /** First hole of each pair (the holes to offer when nothing is selected). */
    private val PAIR_FIRSTS = listOf(TL, TM, BL)

    /** Component id → its selection client var (1 = selected/lit). */
    private val SELECT_VARC = mapOf(TL to 163, TM to 164, TR to 162, BL to 165, BM to 166, BR to 167)

    private val NAME = mapOf(
        TL to "top-left", TM to "top-middle", TR to "top-right",
        BL to "bottom-left", BM to "bottom-middle", BR to "bottom-right",
    )

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        if (!isOpen()) {
            return hint("Open the junction box, then connect the pipe pairs: top-left + bottom-right, top-middle + top-right, bottom-left + bottom-middle.")
        }

        val actions = mutableListOf<QuestAction>(
            QuestAction.TextHint(
                "Connect pipe pairs (click both holes): top-left + bottom-right, top-middle + top-right, bottom-left + bottom-middle."
            )
        )

        val selected = SELECT_VARC.entries.firstOrNull { safeVarc(it.value) == 1 }?.key
        if (selected != null) {
            val partner = PARTNER.getValue(selected)
            actions += highlight(partner, "Connect: ${NAME[partner]}")
        } else {
            for (first in PAIR_FIRSTS) actions += highlight(first, "Start: ${NAME[first]}")
        }
        return QuestStepSolver.Result(overlayActions = actions)
    }

    private fun highlight(componentId: Int, label: String): QuestAction =
        QuestAction.InterfaceComponentHighlight(JUNCTION_BOX, componentId, label = label)

    private fun hint(text: String): QuestStepSolver.Result =
        QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint(text)))

    private fun isOpen(): Boolean = try { interfaces.isOpen(JUNCTION_BOX) } catch (_: Throwable) { false }
    private fun safeVarc(id: Int): Int? = try { varcs.getVar(id) } catch (_: Throwable) { null }
}
