package com.projectx.puzzle.knot

import com.projectx.game.nxt.interfaces.ScreenRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CelticKnotSolverTest {

    private fun layout(sizes: List<Int>, crossings: List<KnotCrossing>, clockwise: Boolean = true): KnotLayout {
        var next = 100
        val tracks = sizes.mapIndexed { index, size ->
            KnotTrack(
                index,
                IntArray(size) { next++ },
                IntArray(size) { next++ },
                clockwiseButton = index * 2,
                counterClockwiseButton = index * 2 + 1,
                forwardIsClockwise = clockwise,
            )
        }
        return KnotLayout(0, 0, tracks, crossings)
    }

    private fun crossing(a: KnotSlotRef, b: KnotSlotRef) = KnotCrossing(a, b, 0)

    private fun ring(vararg runes: Int) = runes

    private fun applied(layout: KnotLayout, moves: List<KnotRotation>): IntArray {
        val offsets = IntArray(layout.tracks.size)
        moves.forEach { move ->
            val forward = if (move.clockwise == move.track.forwardIsClockwise) move.steps else move.track.size - move.steps
            offsets[move.track.index] = forward
        }
        return offsets
    }

    private fun solvedBy(layout: KnotLayout, runes: List<IntArray>, moves: List<KnotRotation>): Boolean {
        val offsets = applied(layout, moves)
        return layout.crossings.all { CelticKnotSolver.aligned(runes, it, offsets) }
    }

    @Test
    fun `already solved needs no rotation`() {
        val l = layout(listOf(4, 4), listOf(crossing(KnotSlotRef(0, 0), KnotSlotRef(1, 0))))
        assertEquals(emptyList(), CelticKnotSolver.solve(l, listOf(ring(7, 1, 2, 3), ring(7, 4, 5, 6))))
    }

    @Test
    fun `finds the rotation that aligns one crossing`() {
        val l = layout(listOf(4, 4), listOf(crossing(KnotSlotRef(0, 0), KnotSlotRef(1, 0))))
        val runes = listOf(ring(1, 2, 7, 3), ring(7, 4, 5, 6))
        assertTrue(solvedBy(l, runes, assertNotNull(CelticKnotSolver.solve(l, runes))))
    }

    @Test
    fun `unsolvable returns null`() {
        val l = layout(listOf(3, 3), listOf(crossing(KnotSlotRef(0, 0), KnotSlotRef(1, 0))))
        assertNull(CelticKnotSolver.solve(l, listOf(ring(1, 2, 3), ring(4, 5, 6))))
    }

    @Test
    fun `an empty slot never constrains a crossing`() {
        val l = layout(listOf(3, 3), listOf(crossing(KnotSlotRef(0, 0), KnotSlotRef(1, 0))))
        assertEquals(emptyList(), CelticKnotSolver.solve(l, listOf(ring(EMPTY_RUNE, 1, 2), ring(4, 5, 6))))
    }

    @Test
    fun `prefers the solution with the fewest clicks`() {
        val l = layout(
            listOf(8, 8),
            listOf(crossing(KnotSlotRef(0, 0), KnotSlotRef(1, 0)), crossing(KnotSlotRef(0, 4), KnotSlotRef(1, 4))),
        )
        val runes = listOf(ring(1, 2, 3, 4, 5, 6, 7, 8), ring(2, 3, 4, 5, 6, 7, 8, 1))
        val moves = assertNotNull(CelticKnotSolver.solve(l, runes))
        assertEquals(1, moves.sumOf { it.steps })
        assertTrue(solvedBy(l, runes, moves))
    }

    @Test
    fun `a counter-clockwise ring maps a forward shift onto the ccw button`() {
        val l = layout(listOf(4, 4), listOf(crossing(KnotSlotRef(0, 1), KnotSlotRef(1, 0))), clockwise = false)
        val runes = listOf(ring(7, 1, 2, 3), ring(7, 4, 5, 6))
        val move = assertNotNull(CelticKnotSolver.solve(l, runes)).single()
        assertEquals(1, move.steps)
        assertEquals(false, move.clockwise)
        assertEquals(move.track.counterClockwiseButton, move.buttonComponent)
        assertTrue(solvedBy(l, runes, listOf(move)))
    }
}

/**
 * Replays a snapshot captured from the live client (every component of `trail_knot_04` with its screen
 * rect and graphic) so layout discovery, rune reading and solving are exercised against real geometry
 * rather than a hand-built fixture.
 */
class CelticKnotLayoutTest {

    private data class Component(val rect: ScreenRect, val graphic: Int)

    private val snapshot: Map<Int, Component> by lazy {
        val csv = checkNotNull(javaClass.getResourceAsStream("/knot/trail_knot_04.csv")) { "missing knot fixture" }
        csv.bufferedReader().readLines().filter { it.isNotBlank() }.associate { line ->
            val f = line.split(',').map { it.toInt() }
            f[0] to Component(ScreenRect(f[1], f[2], f[3], f[4]), f[5])
        }
    }

    private val layout by lazy {
        assertNotNull(CelticKnotLayouts.forInterface(TRAIL_KNOT_04) { snapshot[it]?.rect })
    }

    private val runes by lazy {
        CelticKnotLayouts.readRunes(layout) { snapshot[it]?.graphic ?: EMPTY_RUNE }
    }

    @Test
    fun `every knot interface is discovered from its gameval name`() {
        assertTrue(CelticKnotLayouts.interfaceIds.size >= 9)
        assertTrue(TRAIL_KNOT_04 in CelticKnotLayouts.interfaceIds)
    }

    @Test
    fun `the captured knot yields three rings and eight crossings`() {
        assertEquals(listOf(28, 14, 14), layout.tracks.map { it.size })
        assertEquals(8, layout.crossings.size)
        assertTrue(layout.tracks.all { it.forwardIsClockwise })
    }

    @Test
    fun `a knot whose tiles are not laid out yet produces no layout and is not cached`() {
        val unlaidOut = CelticKnotLayouts.interfaceIds.first { it != TRAIL_KNOT_04 }
        assertNull(CelticKnotLayouts.forInterface(unlaidOut) { null })
        assertNull(CelticKnotLayouts.forInterface(unlaidOut) { null })
    }

    @Test
    fun `the captured knot solves to the shortest set of button presses`() {
        val moves = assertNotNull(CelticKnotSolver.solve(layout, runes))
        assertEquals(
            listOf(Triple(0, 10, false), Triple(1, 1, false), Triple(2, 2, false)),
            moves.map { Triple(it.track.index, it.steps, it.clockwise) },
        )
        assertTrue(moves.all { it.buttonComponent == it.track.counterClockwiseButton })

        val offsets = IntArray(layout.tracks.size)
        moves.forEach { offsets[it.track.index] = it.track.size - it.steps }
        assertTrue(layout.crossings.all { CelticKnotSolver.aligned(runes, it, offsets) })
    }

    @Test
    fun `the captured knot starts with crossings still unaligned`() {
        val settled = IntArray(layout.tracks.size)
        assertEquals(0, layout.crossings.count { CelticKnotSolver.aligned(runes, it, settled) })
    }

    private companion object {
        const val TRAIL_KNOT_04 = 394
    }
}
