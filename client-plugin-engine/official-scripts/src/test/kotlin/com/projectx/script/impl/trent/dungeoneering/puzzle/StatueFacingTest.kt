package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.script.impl.trent.dungeoneering.puzzle.RockPaperScissorsPuzzle.opponentOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import world.gregs.voidps.type.Tile

/** The three real layouts the bot has stood in, taken from the statue tiles it logged in each of them. */
class StatueFacingTest {

    @Test
    fun `ranks split along x pair on the shared y`() {
        val defenders = listOf(Tile.of(9781, 4180), Tile.of(9781, 4183))
        assertEquals(Tile.of(9781, 4180), opponentOf(Tile.of(9785, 4180), defenders))
        assertEquals(Tile.of(9781, 4183), opponentOf(Tile.of(9785, 4183), defenders))
    }

    @Test
    fun `ranks split along y pair on the shared x`() {
        val defenders = listOf(Tile.of(15876, 2425), Tile.of(15879, 2425))
        assertEquals(Tile.of(15876, 2425), opponentOf(Tile.of(15876, 2421), defenders))
        assertEquals(Tile.of(15879, 2425), opponentOf(Tile.of(15879, 2421), defenders))
    }

    @Test
    fun `a rank three apart never pairs across to its neighbour`() {
        val defenders = listOf(Tile.of(9764, 4185), Tile.of(9767, 4185))
        assertEquals(Tile.of(9764, 4185), opponentOf(Tile.of(9764, 4181), defenders))
        assertEquals(Tile.of(9767, 4185), opponentOf(Tile.of(9767, 4181), defenders))
    }

    @Test
    fun `the diagonal defender is closer than nothing but is still not the opponent`() {
        // Distance alone would answer here; sharing neither coordinate means the statues do not face.
        assertNull(opponentOf(Tile.of(9785, 4180), listOf(Tile.of(9781, 4181))))
    }
}
