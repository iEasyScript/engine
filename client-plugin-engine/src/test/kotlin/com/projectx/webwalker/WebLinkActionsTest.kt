package com.projectx.webwalker

import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebLinkActionsTest {

    private fun tile(x: Int, y: Int, plane: Int = 0) = Tile.of(x, y, plane)

    private fun area(x: Int, y: Int, plane: Int = 0) = WebArea(x, x, y, y, plane)

    private fun pick(candidates: List<Tile>, anchor: WebArea, destination: WebArea?, player: Tile = tile(3190, 3190)) =
        WebLinkActions.pickAnchored(candidates, { it }, anchor, destination, player)

    @Test
    fun `the object taken is the one at the link's approach, not the one nearest the player`() {
        val ladders = listOf(tile(3190, 3191), tile(3200, 3203))
        assertEquals(tile(3200, 3203), pick(ladders, area(3201, 3203), null))
    }

    @Test
    fun `twin objects at the approach are told apart by where the link leads`() {
        val twins = listOf(tile(3200, 3200), tile(3202, 3200))
        assertEquals(tile(3202, 3200), pick(twins, area(3201, 3201), area(3203, 3200)))
        assertEquals(tile(3200, 3200), pick(twins, area(3201, 3201), area(3199, 3200)))
    }

    @Test
    fun `a destination on another floor leaves the choice to the approach and the player`() {
        val twins = listOf(tile(3200, 3200), tile(3202, 3200))
        assertEquals(tile(3200, 3200), pick(twins, area(3201, 3201), area(3203, 3200, 1), player = tile(3199, 3200)))
    }

    @Test
    fun `objects further than the slack from the nearest to the approach are never taken`() {
        val objects = listOf(tile(3200, 3200), tile(3205, 3200))
        assertEquals(tile(3200, 3200), pick(objects, area(3200, 3201), area(3205, 3201)))
    }

    @Test
    fun `nothing on the approach's floor picks nothing`() {
        assertNull(pick(listOf(tile(3200, 3200, 1)), area(3200, 3200), null))
        assertNull(pick(emptyList(), area(3200, 3200), null))
    }

    @Test
    fun `distance to an area is zero inside it and Chebyshev outside`() {
        val room = WebArea(3200, 3204, 3200, 3204, 0)
        assertEquals(0, WebLinkActions.areaDistance(room, tile(3202, 3203)))
        assertEquals(3, WebLinkActions.areaDistance(room, tile(3207, 3202)))
        assertEquals(2, WebLinkActions.areaDistance(room, tile(3198, 3206)))
        assertEquals(Int.MAX_VALUE, WebLinkActions.areaDistance(room, tile(3202, 3202, 1)))
    }
}
