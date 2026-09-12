package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.script.api.interfaces
import com.projectx.script.api.varps

/**
 * Step-following solver for the Elemental Workshop III lattice puzzle (interface [PUZZLE]).
 *
 * The puzzle is deterministic - identical after every Reset - so this replays the quest's known
 * per-phase lever sequences as highlights. It reads only state that is position-free and survives the
 * constant instance re-entry (no tile coordinates anywhere):
 *
 *  - **Which group we're in** (right / left / tower) is derived from two game-states and tracked as a
 *    running max so it can't regress: the right counter ([RIGHT_COUNTER]) latches at [COUNTER_TARGET]
 *    once sections 1–2 finish (-> group 1, phase 3), and the "Moves left" counter jumps past its normal
 *    100 cap to [MOVES_UNLIMITED] when section 4 grants unlimited moves (-> group 2, the tower, phase
 *    5). The left/tower counters do NOT cleanly hit 50, so they're not used. This seeds the starting
 *    phase (resume mid-puzzle) and drives every group change.
 *  - **Step within a phase** comes from the "Moves left" counter (comp [MOVES_COMPONENT]): every real
 *    move drops it by exactly one in *all* phases - in the tower it just counts down from the 101 the
 *    grant put it at (it is not frozen). An Undo bumps it one; a Reset refund / the +50 grant jump it
 *    up by more, which never advances the step.
 *
 * Why this is robust where counting moves alone wasn't: group boundaries key off reaching those
 * states (right counter latched, move counter past 100), tracked monotonically - so the +50 grant
 * after section 2 and the unlimited-moves jump after section 4 can't be mistaken for, or skip, a
 * section. Within a group the first phase advances on the Reset after its finished sequence.
 *
 * Highlight-only. All state is touched only from the main-logic thread ([evaluate]).
 */
object ElementalWorkshopIIILatticeSolver : QuestStepSolver {
    override val id: String = "elemental-workshop-iii.lattice"

    private const val PUZZLE = 1689
    private const val MOVES_COMPONENT = 17
    private const val RESET_COMPONENT = 48
    private const val STAGE_VARBIT = 12504
    private const val STAGE_COMPLETE = 5

    private const val RIGHT_COUNTER = 12510
    private const val COUNTER_TARGET = 50

    // Sections 1–4 cap at 100 moves (50 base + 50 after section 2). Section 4 grants "unlimited",
    // which shows up as the counter jumping past 100 (to 101) - the boundary into the tower group.
    private const val MOVES_UNLIMITED = 101

    // Wiki labelled-levers diagram: rows A–F run north->south = left levers comp 15->10;
    // columns 1–7 run west->east = bottom levers comp 9->3.
    private val LEVER_COMP = mapOf(
        "A" to 15, "B" to 14, "C" to 13, "D" to 12, "E" to 11, "F" to 10,
        "1" to 9, "2" to 8, "3" to 7, "4" to 6, "5" to 5, "6" to 4, "7" to 3,
    )

    private class Phase(val name: String, raw: String) {
        /** One label per char (A–F row lever, 1–7 column lever). */
        val steps: List<String> = raw.filter { it.isLetterOrDigit() }.map { it.uppercase() }
    }

    // Canonical wiki solutions (deterministic), paired into the three counter groups (right, left,
    // tower) - two phases each. The first phase of a pair advances on a Reset, the second when its
    // counter completes.
    private val PHASES = listOf(
        Phase("Right counter, north", "C2F6 C2F6 C2E5 B3F2 B3F2 E4F3"),
        Phase("Right counter, south", "A5C6 B5D6 C5E6 C5F6 D5E3 F4E"),
        Phase("Left counter, north", "2B6A 5B6C 5B2C 5B3E 6F5B 3C6F 2C6E 3B5E 6F4E 6F4E 5"),
        Phase("Left counter, south", "2C3B 2A3E 2B3F 2C3F 2C5F 2E"),
        Phase("Lower the tower", "B2A3 B2F6 B2D3 F6C5 D6F5 C6F2 E5F2 E5D6 F4E6"),
        Phase("Spin & raise the tower", "4E6F 4E6A 5B6E 5A6F 5B2A 3B4C 2B6E 5C2D 3E5C 3F6 B4C3"),
    )

    private var phase = 0
    private var step = 0
    private var prevMoves = -1
    private var maxGroup = -1

    override fun evaluate(quest: Quest, step0: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        if (!isOpen()) {
            resetTracking()
            return hint("Open the elemental lattice puzzle - the solver guides each lever.")
        }
        if (stage() >= STAGE_COMPLETE) return solved("Lattice puzzle complete - proceed with the quest.")
        // Once the tower is spun and raised the move counter goes blank; the puzzle is parked here.
        // Do NOT pull Reset (it would undo the raise) - the player leaves for the 'Body body' steps.
        if (towerRaised()) return solved(
            "Tower spun and raised - do NOT pull the Reset lever. Head upstairs to make a body bar (the 'Body body' steps).",
        )

        trackGroup()
        trackStep()

        if (phase > PHASES.lastIndex) return solved("Lattice puzzle complete - proceed with the quest.")
        val ph = PHASES[phase]
        val s = step.coerceIn(0, ph.steps.size)
        if (s >= ph.steps.size) {
            // The final section ends by raising the tower, which is detected above - never tell the
            // player to Reset out of it.
            if (phase == PHASES.lastIndex) {
                return hint("Finish spinning and raising the tower - do NOT pull Reset.")
            }
            return result(
                "Phase ${phase + 1}/${PHASES.size} (${ph.name}) done - pull the Reset lever (confirm) to continue.",
                RESET_COMPONENT, "Reset",
            )
        }
        val label = ph.steps[s]
        val comp = LEVER_COMP[label] ?: return hint("Lever '$label' is not mapped.")
        return result(
            "Phase ${phase + 1}/${PHASES.size} (${ph.name}) - step ${s + 1}/${ph.steps.size}: pull lever $label.",
            comp, "Pull $label",
        )
    }

    /** The stuck right-counter / unlimited-moves states (monotonic) seed the start phase and drive boundaries. */
    private fun trackGroup() {
        val g = currentGroup() ?: return
        if (maxGroup < 0) {                 // first read this session - resume at the right group
            maxGroup = g
            phase = (g * 2).coerceAtMost(PHASES.lastIndex)
            step = 0
            return
        }
        if (g > maxGroup) {                 // boundary crossed - start the next group
            maxGroup = g
            phase = (g * 2).coerceAtMost(PHASES.lastIndex)
            step = 0
        }
    }

    private fun currentGroup(): Int? = try {
        val moves = readMoves()
        when {
            moves != null && moves >= MOVES_UNLIMITED -> 2                  // unlimited moves -> tower
            varps.getVarBit(RIGHT_COUNTER) >= COUNTER_TARGET -> 1           // right counter latched -> left
            else -> 0
        }
    } catch (_: Throwable) { null }

    private fun trackStep() {
        val moves = readMoves() ?: return
        if (prevMoves >= 0) {
            val delta = moves - prevMoves
            val len = PHASES.getOrNull(phase)?.steps?.size ?: 0
            when {
                delta < 0 -> step -= delta                                  // move(s) made (−1 each)
                delta == 1 -> step = (step - 1).coerceAtLeast(0)            // Undo
                delta > 1 -> {                                              // Reset refund / +50 grant
                    // Only a group's first (even) phase advances on the Reset; its last (odd) phase
                    // waits for the group boundary, so the +50 grant can't skip it.
                    if (phase % 2 == 0 && step >= len && phase < PHASES.lastIndex) phase++
                    step = 0
                }
            }
        }
        prevMoves = moves
    }

    private fun readMoves(): Int? = try {
        interfaces.getComponent(PUZZLE, MOVES_COMPONENT)?.text?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
    } catch (_: Throwable) { null }

    /** The "Moves left" label is present but shows no number only once the tower is fully raised. */
    private fun towerRaised(): Boolean = try {
        val t = interfaces.getComponent(PUZZLE, MOVES_COMPONENT)?.text
        t != null && t.contains("Moves") && t.none { it.isDigit() }
    } catch (_: Throwable) { false }

    private fun stage(): Int = try { varps.getVarBit(STAGE_VARBIT) } catch (_: Throwable) { 0 }

    private fun resetTracking() {
        phase = 0; step = 0; prevMoves = -1; maxGroup = -1
    }

    private fun result(text: String, comp: Int, label: String) = QuestStepSolver.Result(
        overlayActions = listOf(QuestAction.TextHint(text), QuestAction.InterfaceComponentHighlight(PUZZLE, comp, label = label)),
    )

    private fun hint(text: String) = QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint(text)))
    private fun solved(text: String) = QuestStepSolver.Result(solved = true, overlayActions = listOf(QuestAction.TextHint(text)))
    private fun isOpen(): Boolean = try { interfaces.isOpen(PUZZLE) } catch (_: Throwable) { false }
}
