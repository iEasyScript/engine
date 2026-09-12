package com.projectx.script.impl.trent.dungeoneering

import com.projectx.script.impl.trent.dungeoneering.map.MapCell
import com.projectx.script.impl.trent.dungeoneering.map.MapIcons
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectivityTest {

    private fun known(openings: Int) = MapCell(0, 0).apply { this.openings = openings; openingsKnown = true }
    private fun partial(openings: Int) = MapCell(0, 0).apply { this.openings = openings; openingsKnown = false }

    private val N = MapIcons.NORTH
    private val E = MapIcons.EAST
    private val S = MapIcons.SOUTH
    private val W = MapIcons.WEST

    @Test
    fun `two known rooms connect only when both face each other`() {
        // A has an east door, B (east of A) has a west door back.
        assertTrue(CriticalityAnalyzer.connects(known(E), known(W), E))
        // A dead-end facing south does NOT connect east even if the eastern room opens west.
        assertFalse(CriticalityAnalyzer.connects(known(S), known(W), E))
    }

    @Test
    fun `a known room positively lacking the door blocks the passage`() {
        assertFalse(CriticalityAnalyzer.connects(known(0), partial(MapIcons.ALL_OPENINGS), E))
        assertFalse(CriticalityAnalyzer.connects(partial(MapIcons.ALL_OPENINGS), known(0), E))
    }

    @Test
    fun `a partial cell missing the door still connects via an authoritative neighbour`() {
        // Start (openings 0, partial) with a decoded room to its east that has a west door => connected.
        assertTrue(CriticalityAnalyzer.connects(partial(0), known(W), E))
        // "?" room whose single known door faces the neighbour connects even if the neighbour is partial.
        assertTrue(CriticalityAnalyzer.connects(partial(E), partial(0), E))
    }

    @Test
    fun `two partial cells with no door between them do not falsely connect`() {
        assertFalse(CriticalityAnalyzer.connects(partial(0), partial(0), E))
        assertFalse(CriticalityAnalyzer.connects(partial(N), partial(N), E))
    }
}
