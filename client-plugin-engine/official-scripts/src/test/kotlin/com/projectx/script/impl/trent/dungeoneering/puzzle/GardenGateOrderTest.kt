package com.projectx.script.impl.trent.dungeoneering.puzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gate choice rests on one claim: the gap from the big flower's colour forward to a gate's colour orders the
 * gates by how soon each becomes cuttable. It holds for any rate ratio where the big flower is the faster of
 * the two, so nothing here depends on that ratio being exactly 2:1.
 */
class GardenGateOrderTest {

    private val order = listOf("blue", "purple", "red", "yellow")

    private fun gap(gate: String, big: String) = OvergrownGardenPuzzle.coloursUntilMatch(gate, big)

    @Test
    fun `a gate already on the big flower's colour is cuttable now`() {
        order.forEach { assertEquals(0, gap(it, it)) }
    }

    @Test
    fun `the gap counts forward through the cycle and wraps`() {
        assertEquals(1, gap("purple", "blue"))
        assertEquals(2, gap("red", "blue"))
        assertEquals(3, gap("yellow", "blue"))
        assertEquals(1, gap("blue", "yellow"))
    }

    /**
     * Simulates the cycle directly, stepping the big flower faster than the gates. Whatever whole-number rate
     * the big flower runs at, the gate the rule picks must never be beaten to a match by one it passed over.
     */
    @Test
    fun `the smallest gap really does match first at any faster big-flower rate`() {
        for (bigRate in 2..5) {
            for (bigStart in order.indices) {
                val matchTick = order.indices.associateWith { gateStart ->
                    (0..400).first { tick ->
                        order[(bigStart + tick * bigRate / 10) % 4] == order[(gateStart + tick / 10) % 4]
                    }
                }
                val ranked = order.indices.sortedBy { gap(order[it], order[bigStart]) }
                val chosen = ranked.first()
                ranked.forEach { other ->
                    assertTrue(
                        matchTick.getValue(chosen) <= matchTick.getValue(other),
                        "rate $bigRate big=${order[bigStart]}: picked ${order[chosen]} but ${order[other]} matched sooner",
                    )
                }
            }
        }
    }
}
