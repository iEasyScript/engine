package com.projectx.puzzle.invention

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventionDiscoveryModelTest {

    @Test
    fun parsesLiveServerResults() {
        val excellent = OptimisationText.parse(
            "<col=ffffff>Optimisation: Excellent<br>You will gain : <col=ffffff>308</col> + <col=ffffff>924</col> extra XP."
        )
        assertNotNull(excellent)
        assertEquals("Excellent", excellent.level)
        assertEquals(308, excellent.baseXp)
        assertEquals(924, excellent.bonusXp)
        assertTrue(!excellent.perfect)

        val perfect = OptimisationText.parse(
            "<col=ffffff>Optimisation: Perfect<br>You will gain : <col=ffffff>308</col> + <col=ffffff>1232</col> extra XP."
        )
        assertNotNull(perfect)
        assertTrue(perfect.perfect)
    }

    @Test
    fun pendingAndBlankAreNotResults() {
        assertNull(OptimisationText.parse("Analysing..."))
        assertNull(OptimisationText.parse(""))
        assertTrue(OptimisationText.isPending("Analysing..."))
    }

    @Test
    fun oneSwapAtATimeReachesPerfect() {
        for (seed in 0 until 20) {
            val random = Random(seed)
            val blueprint = Blueprint(random)
            val swaps = solve(blueprint, blueprint.arrangement(random.nextInt(ARRANGEMENTS.size)))
            assertTrue(swaps <= SWAP_LIMIT, "blueprint $seed needed $swaps swaps")
        }
    }

    @Test
    fun stopsAdvisingOncePerfectIsOnTheTrack() {
        val model = InventionDiscoveryModel()
        val arrangement = intArrayOf(3, 1, 4, 5, 2)
        model.record(arrangement, 0)
        val advice = model.advise(arrangement)
        assertTrue(advice.solved)
        assertTrue(model.solved)
        assertEquals(-1, advice.swapFirst)
    }

    @Test
    fun tracksTheBestArrangementSeen() {
        val model = InventionDiscoveryModel()
        model.record(intArrayOf(1, 2, 3, 4, 5), 8)
        model.record(intArrayOf(2, 1, 3, 4, 5), 4)
        model.record(intArrayOf(2, 1, 3, 5, 4), 6)
        assertEquals(4, model.bestPenalty)
        assertTrue(model.bestArrangement.contentEquals(intArrayOf(2, 1, 3, 4, 5)))
        assertTrue(!model.solved)
    }

    /** Drives the model exactly as the script does: apply the advised swap, re-score, repeat. */
    private fun solve(blueprint: Blueprint, start: IntArray): Int {
        val model = InventionDiscoveryModel()
        var current = start
        var swaps = 0
        while (swaps <= SWAP_LIMIT) {
            model.record(current, blueprint.penaltyOf(current))
            val advice = model.advise(current)
            if (advice.solved) return swaps
            assertTrue(advice.swapFirst in 0 until TRACK_SLOTS)
            assertTrue(advice.swapSecond in 0 until TRACK_SLOTS)
            assertTrue(advice.swapFirst != advice.swapSecond)
            current = current.copyOf().also {
                val held = it[advice.swapFirst]
                it[advice.swapFirst] = it[advice.swapSecond]
                it[advice.swapSecond] = held
            }
            swaps++
        }
        return swaps
    }

    /**
     * Stands in for the server's scoring: an affinity per (slot, module) whose sum over an arrangement
     * is that arrangement's penalty, normalised so the single best arrangement scores zero. That
     * additive shape is the whole reason the model can fit rather than guess.
     */
    private class Blueprint(random: Random) {
        private val affinity: IntArray
        private val floor: Int

        init {
            var candidate: IntArray
            var penalties: List<Int>
            do {
                candidate = IntArray(TRACK_SLOTS * TRACK_SLOTS) { random.nextInt(0, 4) }
                penalties = ARRANGEMENTS.map { perm -> sum(candidate, perm) }
            } while (penalties.count { it == penalties.min() } != 1)
            affinity = candidate
            floor = penalties.min()
        }

        fun penaltyOf(perm: IntArray) = sum(affinity, perm) - floor

        fun arrangement(index: Int): IntArray = ARRANGEMENTS[index].copyOf()

        private fun sum(table: IntArray, perm: IntArray): Int {
            var total = 0
            for (slot in perm.indices) total += table[slot * TRACK_SLOTS + perm[slot] - 1]
            return total
        }
    }

    private companion object {
        const val SWAP_LIMIT = 60
        val ARRANGEMENTS = InventionDiscoveryModel.ALL_ARRANGEMENTS
    }
}
