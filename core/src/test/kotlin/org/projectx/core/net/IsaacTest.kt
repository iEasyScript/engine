package org.projectx.core.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Known-answer regression tests for the ISAAC opcode cipher.
 *
 * The expected vectors were generated from this implementation at the point where it was
 * verified bidirectionally against the live NXT client (rev 948 captures decode to EOF with
 * zero desync using these outputs). Any change to these sequences WILL desync the protocol.
 */
class IsaacTest {

    private data class Vector(val name: String, val seed: IntArray, val first8: IntArray, val at300: Int)

    private val vectors = listOf(
        Vector(
            "zero seed",
            intArrayOf(0, 0, 0, 0),
            intArrayOf(405143795, 806046349, 807101986, -1333080799, 695195257, -1722677527, -1275090763, 264870948),
            2117163755,
        ),
        Vector(
            "sequential seed",
            intArrayOf(1, 2, 3, 4),
            intArrayOf(-621246914, 1957022519, -1345000077, -2021884860, -1882702437, 1616913581, -8779862, 1337573575),
            1519510071,
        ),
        Vector(
            "high-bit seed",
            intArrayOf(0xDEADBEEF.toInt(), 0x12345678, 0xCAFEBABE.toInt(), 0x0BADF00D),
            intArrayOf(-1242666009, -890232808, -543510491, -1088373860, 1595070166, 571044832, -1353745632, -288418773),
            -1951147896,
        ),
        Vector(
            "sequential seed + ISAAC delta 50 (S->C convention)",
            intArrayOf(51, 52, 53, 54),
            intArrayOf(570203416, 2055224943, 1668339871, -912945926, 722672204, -1566149819, 773294658, -1565970591),
            1442168052,
        ),
    )

    @Test
    fun `known answer - first 8 outputs per seed`() {
        for (v in vectors) {
            val isaac = Isaac(v.seed.copyOf())
            val actual = IntArray(8) { isaac.nextInt() }
            assertContentEquals(v.first8, actual, "first 8 outputs for ${v.name}")
        }
    }

    @Test
    fun `known answer - 300th output crosses the 256-result regeneration boundary`() {
        for (v in vectors) {
            val isaac = Isaac(v.seed.copyOf())
            var value = 0
            repeat(300) { value = isaac.nextInt() }
            assertEquals(v.at300, value, "300th output for ${v.name}")
        }
    }

    @Test
    fun `same seed produces identical streams`() {
        val a = Isaac(intArrayOf(7, 8, 9, 10))
        val b = Isaac(intArrayOf(7, 8, 9, 10))
        repeat(600) { i ->
            assertEquals(a.nextInt(), b.nextInt(), "output #$i diverged")
        }
    }

    @Test
    fun `delta-shifted seed produces a different stream`() {
        val raw = Isaac(intArrayOf(1, 2, 3, 4))
        val shifted = Isaac(intArrayOf(51, 52, 53, 54))
        val rawFirst = IntArray(8) { raw.nextInt() }
        val shiftedFirst = IntArray(8) { shifted.nextInt() }
        assertNotEquals(rawFirst.toList(), shiftedFirst.toList())
    }

    @Test
    fun `seed property is a defensive copy of the input`() {
        val input = intArrayOf(1, 2, 3, 4)
        val isaac = Isaac(input)
        input[0] = 999
        assertContentEquals(intArrayOf(1, 2, 3, 4), isaac.seed)
    }
}
