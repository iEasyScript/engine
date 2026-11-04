package com.projectx.traversal

import com.projectx.traversal.nodes.NodeWaits
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeWaitsTest {

    @Test
    fun `click gaps vary around their mean and never collapse`() {
        val gaps = (1..2_000).map { NodeWaits.clickGap(600) }
        assertTrue(gaps.all { it >= 150 })
        assertTrue(gaps.toSet().size > 50)
        assertTrue(gaps.average() in 550.0..650.0)
    }

    @Test
    fun `within never counts a tile on another floor`() {
        assertTrue(NodeWaits.within(Tile.of(3200, 3200, 0), Tile.of(3203, 3200, 0), 5))
        assertFalse(NodeWaits.within(Tile.of(3200, 3200, 1), Tile.of(3200, 3200, 0), 5))
        assertFalse(NodeWaits.within(Tile.of(3200, 3200, 0), Tile.of(3206, 3200, 0), 5))
    }
}
