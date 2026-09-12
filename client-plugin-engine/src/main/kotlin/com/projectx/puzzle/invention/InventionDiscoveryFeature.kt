package com.projectx.puzzle.invention

import com.projectx.puzzle.invention.InventionDiscoveryOverlayState.Companion.INTERFACE_ID
import com.projectx.script.api.interfaces
import com.projectx.util.FeatureIds
import com.projectx.script.api.varps
import world.gregs.voidps.gameval.Gameval

/**
 * Watches the Invention discovery track each main-logic tick, feeds every scored arrangement to
 * [InventionDiscoveryModel] and publishes an advisory [InventionDiscoveryOverlayState] naming the two
 * modules to swap next. It never clicks anything — the Auto Discover script does that, and owns its
 * own reading of the screen so that side can be iterated on without rebuilding the engine.
 */
object InventionDiscoveryFeature {

    private const val ARRANGING_MODE = 2
    private const val SCORE_COMPONENT = 4

    private class Ids(val mode: Int, val score: Int, val trackSlots: List<Int>, val blueprint: Int)

    private val ids = FeatureIds("InventionDiscovery") {
        Ids(
            mode = Gameval.requireId(Gameval.VARBIT, "invent_discovery_mode"),
            score = Gameval.requireId(Gameval.VARBIT, "invent_discovery_score"),
            trackSlots = (0 until TRACK_SLOTS).map { Gameval.requireId(Gameval.VARBIT, "invent_discovery_order_slot_$it") },
            blueprint = Gameval.requireId(Gameval.VAR_PLAYER, "invent_blueprint_selected"),
        )
    }

    private val model = InventionDiscoveryModel()
    private var blueprint = -1

    @Volatile
    var overlay: InventionDiscoveryOverlayState? = null
        private set

    fun tick(enabled: Boolean) {
        if (!enabled || !isOpen()) { clear(); return }
        val ids = ids.get() ?: run { clear(); return }

        val current = runCatching { varps.getVar(ids.blueprint) }.getOrDefault(-1)
        if (current != blueprint) {
            blueprint = current
            model.reset()
        }

        if (bit(ids.mode) != ARRANGING_MODE) { overlay = null; return }

        val arrangement = IntArray(TRACK_SLOTS) { bit(ids.trackSlots[it]) }
        if (!InventionDiscoveryModel.isArrangement(arrangement)) {
            overlay = InventionDiscoveryOverlayState(
                Array(TRACK_SLOTS) { SlotHint.NEUTRAL },
                listOf("Place all five modules on the track to begin."),
                solved = false,
            )
            return
        }

        val score = OptimisationText.parse(scoreText())
        if (score != null) model.record(arrangement, bit(ids.score))

        val advice = model.advise(arrangement)
        overlay = InventionDiscoveryOverlayState(advice.slotHints, buildLines(score, advice), advice.solved)
    }

    private fun buildLines(score: Optimisation?, advice: Advice): List<String> {
        val lines = ArrayList<String>()
        if (score != null) lines += "Optimisation: ${score.level} · +${score.bonusXp} xp"
        when {
            advice.solved -> lines += "Perfect - click Invent."
            advice.swapFirst >= 0 && advice.swapSecond >= 0 ->
                lines += "Probe: swap slot ${advice.swapFirst + 1} <-> slot ${advice.swapSecond + 1}"
            else -> lines += "Analysing arrangements..."
        }
        if (!advice.solved) lines += "Learning: ${model.observationCount} tried"
        return lines
    }

    private fun isOpen() = runCatching { interfaces.isOpen(INTERFACE_ID) }.getOrDefault(false)

    private fun bit(id: Int) = runCatching { varps.getVarBit(id) }.getOrDefault(0)

    private fun scoreText() =
        runCatching { interfaces.getComponent(INTERFACE_ID, SCORE_COMPONENT)?.text }.getOrNull() ?: ""

    private fun clear() {
        overlay = null
        blueprint = -1
        model.reset()
    }
}
