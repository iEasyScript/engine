package com.projectx.puzzle.combolock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CombinationLockTest {

    @Test
    fun shortestTurnPicksTheCheaperDirection() {
        assertEquals(DialMove(TurnDirection.FORWARD, 0), CombinationLock.move('A', 'A'))
        assertEquals(DialMove(TurnDirection.FORWARD, 3), CombinationLock.move('A', 'D'))
        assertEquals(DialMove(TurnDirection.BACKWARD, 1), CombinationLock.move('A', 'Z'))
        assertEquals(DialMove(TurnDirection.BACKWARD, 3), CombinationLock.move('D', 'A'))
        // 13 each way → ties resolve forward.
        assertEquals(DialMove(TurnDirection.FORWARD, 13), CombinationLock.move('A', 'N'))
        assertEquals(DialMove(TurnDirection.BACKWARD, 12), CombinationLock.move('A', 'O'))
    }

    @Test
    fun singleItemUsesItsFourLetterWord() {
        assertEquals("DART", derive("Bronze dart"))
        assertEquals("BEER", derive("Beer"))
        assertEquals("FISH", derive("Swordfish"))
    }

    @Test
    fun cleanHerbIsAlwaysHerb() {
        assertEquals("HERB", derive("Guam leaf"))
        assertEquals("HERB", derive("Clean ranarr"))
    }

    @Test
    fun fourItemsUseFirstLettersFarthestToClosest() {
        // Anchor (chest) at (0,0). Distances: Plank=10, Jute=7, Catfish=4, Diamond=1.
        val items = listOf(
            ClueItem("Diamond bolt", 1, 1, 0),
            ClueItem("Catfish", 1, 4, 0),
            ClueItem("Jute fibres", 1, 7, 0),
            ClueItem("Plank", 1, 10, 0),
        )
        assertEquals("PJCD", CombinationClueReader.derive(items, 0, 0))
    }

    @Test
    fun coinPilesBecomeRomanNumeralsClockwiseFromNorth() {
        // Anchor (0,0). Piles N(0,5)=100→C, E(5,0)=500→D, S(0,-5)=1→I, W(-5,0)=10→X.
        val items = listOf(
            ClueItem("Coins", 1, 0, -5),
            ClueItem("Coins", 10, -5, 0),
            ClueItem("Coins", 100, 0, 5),
            ClueItem("Coins", 500, 5, 0),
        )
        assertEquals("CDIX", CombinationClueReader.derive(items, 0, 0))
    }

    @Test
    fun undeterminableCluesReturnNull() {
        assertNull(CombinationClueReader.derive(emptyList(), 0, 0))
        // Two non-coin items can't fill four dials by first-letter.
        assertNull(CombinationClueReader.derive(listOf(ClueItem("Plank", 1, 1, 0), ClueItem("Rope", 1, 2, 0)), 0, 0))
        // A coin pile with a non-single-numeral amount.
        assertNull(CombinationClueReader.derive(List(4) { ClueItem("Coins", 7, it, 0) }, 0, 0))
    }

    private fun derive(singleItemName: String) = CombinationClueReader.derive(listOf(ClueItem(singleItemName, 1, 1, 0)), 0, 0)
}
