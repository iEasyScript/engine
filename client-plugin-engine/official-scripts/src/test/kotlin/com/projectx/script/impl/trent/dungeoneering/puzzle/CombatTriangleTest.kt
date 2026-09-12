package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.script.impl.trent.dungeoneering.puzzle.RockPaperScissorsPuzzle.BEATS
import com.projectx.script.impl.trent.dungeoneering.puzzle.RockPaperScissorsPuzzle.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CombatTriangleTest {

    private val styles = Style.entries.toSet()

    @Test
    fun `every style has exactly one counter and none counters itself`() {
        assertEquals(styles, BEATS.keys)
        assertEquals(styles, BEATS.values.toSet())
        BEATS.forEach { (defender, attacker) -> assertNotEquals(defender, attacker) }
    }

    @Test
    fun `countering three times returns to the start`() {
        // A proper 3-cycle: a typo that made two styles counter each other would settle into a 2-cycle here.
        styles.forEach { start ->
            val once = BEATS.getValue(start)
            val twice = BEATS.getValue(once)
            assertNotEquals(start, twice)
            assertEquals(start, BEATS.getValue(twice))
        }
    }

    @Test
    fun `the counter matches the live-confirmed reading of the triangle`() {
        assertEquals(Style.RANGE, BEATS.getValue(Style.MAGIC))
        assertEquals(Style.MELEE, BEATS.getValue(Style.RANGE))
        assertEquals(Style.MAGIC, BEATS.getValue(Style.MELEE))
    }

    @Test
    fun `each style carves through its own option into its own item`() {
        assertEquals(styles.size, Style.entries.map { it.carved }.toSet().size)
        assertEquals(styles.size, Style.entries.map { it.carveOp }.toSet().size)
    }
}
