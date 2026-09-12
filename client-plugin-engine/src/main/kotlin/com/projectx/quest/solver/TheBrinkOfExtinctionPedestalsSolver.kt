package com.projectx.quest.solver

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestStep
import com.projectx.quest.data.WorldLocation
import com.projectx.script.api.getAllObjectsWithinRange
import world.gregs.voidps.type.Tile

/**
 * "Touch the pedestals in the order that they light up" - the memory (Simon-says) puzzle in
 * *The Brink of Extinction*. Four `tzhaar3_memory_pedestal` locs sit in a 2×2 grid; the server
 * flashes them one at a time to show a sequence, then the player repeats it by touching them in
 * that order. Each round replays the previous sequence with one new pedestal appended, over eight
 * rounds.
 *
 * A pedestal at rest is loc [ID_OFF] (its "Touch" option present); while lit it morphs to [ID_ON]
 * (no "Touch" option, flash animation). So the "which lit and in what order" signal is read purely
 * off the live loc id at each pedestal's tile - no varbit or interface backs it.
 *
 * Each pedestal is given a stable number 1..N by sorting its tile, mirroring the wiki tip to
 * number the pedestals and write down the flash order. The solver watches the flashes, records the
 * order, and once a show finishes surfaces the touch order plus a per-pedestal number highlight so
 * the player can follow it.
 *
 * Robustness: flashes are grouped into a "burst" (a run of rising edges with no [BURST_GAP_MS] gap).
 * The authoritative [sequence] is updated by prefix-consistency - a burst that extends it grows it,
 * a partial/prefix burst (the player mid-input, or a stray) is ignored, and a genuinely divergent
 * burst of length ≥ 2 (a fresh attempt after a failure) replaces it. This holds whether or not a
 * player's own touch re-lights a pedestal, and survives the ~250ms solver cadence.
 */
object TheBrinkOfExtinctionPedestalsSolver : QuestStepSolver {
    override val id: String = "the-brink-of-extinction.memory-pedestals"

    private const val ID_OFF = 80781
    private const val ID_ON = 80782
    private const val SCAN_RANGE = 15
    private const val BURST_GAP_MS = 2200L

    private data class Pedestal(val number: Int, val tile: Tile, val typeId: Int, val lit: Boolean)

    private var tileKey: String = ""
    private val litTiles = HashSet<Tile>()
    private val burst = ArrayList<Int>()
    private var burstClosed = true
    private var lastFlashMs = 0L
    private var sequence: List<Int> = emptyList()

    override fun evaluate(quest: Quest, step: QuestStep, stepIndex: Int): QuestStepSolver.Result {
        val pedestals = readPedestals()
        if (pedestals.size < 2) {
            resetState()
            return hint("Enter the pedestal room - I'll watch which ones light up and record the order.")
        }

        val key = pedestals.joinToString(",") { "${it.tile.x}:${it.tile.y}:${it.tile.plane}" }
        if (key != tileKey) resetState().also { tileKey = key }

        val now = System.currentTimeMillis()
        recordFlashes(pedestals, now)
        if (!burstClosed && burst.isNotEmpty() && now - lastFlashMs > BURST_GAP_MS) {
            mergeBurst(burst)
            burstClosed = true
        }

        val litNow = pedestals.filter { it.lit }
        if (!burstClosed && burst.isNotEmpty()) {
            val watching = QuestAction.TextHint("Watching sequence: ${burst.joinToString(" ")}")
            return QuestStepSolver.Result(overlayActions = listOf(watching) + litNow.map { highlight(it, it.number.toString()) })
        }

        if (sequence.isEmpty()) return hint("Touch a pedestal to start, then watch the flash order.")

        val order = QuestAction.TextHint("Touch order: ${sequence.joinToString(" -> ")}")
        val legend = pedestals.sortedBy { it.number }.map { highlight(it, it.number.toString()) }
        return QuestStepSolver.Result(overlayActions = listOf(order) + legend)
    }

    private fun readPedestals(): List<Pedestal> {
        val objs = runCatching { getAllObjectsWithinRange(SCAN_RANGE) }.getOrNull().orEmpty()
            .filter { it.id == ID_OFF || it.id == ID_ON }
            .distinctBy { it.tile }
            .sortedWith(compareBy({ it.tile.plane }, { it.tile.x }, { it.tile.y }))
        return objs.mapIndexed { i, obj -> Pedestal(i + 1, obj.tile, obj.visibleTypeId, obj.id == ID_ON) }
    }

    private fun recordFlashes(pedestals: List<Pedestal>, now: Long) {
        val risen = pedestals.filter { it.lit && it.tile !in litTiles }.sortedBy { it.number }
        litTiles.clear()
        pedestals.filter { it.lit }.forEach { litTiles += it.tile }
        for (p in risen) {
            if (burstClosed || now - lastFlashMs > BURST_GAP_MS) {
                burst.clear()
                burstClosed = false
            }
            burst += p.number
            lastFlashMs = now
        }
    }

    private fun mergeBurst(b: List<Int>) {
        if (b == sequence) return
        if (b.size > sequence.size && b.subList(0, sequence.size) == sequence) {
            sequence = b.toList()
        } else if (b.size <= sequence.size && sequence.subList(0, b.size) == b) {
            return
        } else if (b.size >= 2 || sequence.isEmpty()) {
            sequence = b.toList()
        }
    }

    private fun highlight(p: Pedestal, label: String) = QuestAction.ModelHighlight(
        kind = "object",
        typeId = p.typeId,
        modelIds = emptyList(),
        displayName = label,
        atLocation = WorldLocation(p.tile.x.toDouble(), 0.0, p.tile.y.toDouble()),
    )

    private fun resetState(): Unit {
        litTiles.clear()
        burst.clear()
        burstClosed = true
        lastFlashMs = 0L
        sequence = emptyList()
    }

    private fun hint(text: String) = QuestStepSolver.Result(overlayActions = listOf(QuestAction.TextHint(text)))
}
