package com.projectx.script.impl.trent.dungeoneering.puzzle

import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IceSlideTest {

    // Walkable interior [0, w) x [0, h); everything outside (the walls) and any pillar is blocked.
    private fun grid(w: Int, h: Int, vararg pillars: Pair<Int, Int>): (Int, Int) -> Boolean {
        val p = pillars.toHashSet()
        return { x, y -> x < 0 || y < 0 || x >= w || y >= h || (x to y) in p }
    }

    private fun tile(x: Int, y: Int) = Tile.of(x, y, 0)

    private fun replay(from: Tile, dirs: List<SlideDir>, blocked: (Int, Int) -> Boolean): Tile {
        var t = from
        for (d in dirs) t = IceSlide.landing(t, d, blocked)
        return t
    }

    @Test
    fun `slides to the far wall`() {
        val g = grid(5, 5)
        assertEquals(tile(4, 0), IceSlide.landing(tile(0, 0), SlideDir.EAST, g))
        assertEquals(tile(0, 4), IceSlide.landing(tile(0, 0), SlideDir.NORTH, g))
        assertEquals(tile(0, 0), IceSlide.landing(tile(4, 4), SlideDir.WEST, g).let { IceSlide.landing(it, SlideDir.SOUTH, g) })
    }

    @Test
    fun `no move when the next tile is blocked`() {
        val g = grid(5, 5)
        assertEquals(tile(0, 0), IceSlide.landing(tile(0, 0), SlideDir.SOUTH, g)) // wall immediately south
        assertEquals(tile(0, 0), IceSlide.landing(tile(0, 0), SlideDir.WEST, g))  // wall immediately west
    }

    @Test
    fun `stops on the tile before a pillar`() {
        val g = grid(5, 5, 2 to 2)
        assertEquals(tile(1, 2), IceSlide.landing(tile(0, 2), SlideDir.EAST, g)) // pillar at (2,2) stops it
        assertEquals(tile(3, 2), IceSlide.landing(tile(4, 2), SlideDir.WEST, g))
        assertEquals(tile(2, 1), IceSlide.landing(tile(2, 0), SlideDir.NORTH, g))
        assertEquals(tile(2, 3), IceSlide.landing(tile(2, 4), SlideDir.SOUTH, g))
    }

    @Test
    fun `path reaches a reachable rest tile`() {
        val g = grid(5, 5)
        val dirs = IceSlide.path(tile(0, 0), tile(4, 4), g)
        assertTrue(dirs != null && dirs.isNotEmpty())
        assertEquals(tile(4, 4), replay(tile(0, 0), dirs!!, g))
    }

    @Test
    fun `path routes around a pillar`() {
        // Pillar row splits the room; the only rest tiles beyond it are reached by going around.
        val g = grid(5, 5, 2 to 1, 2 to 2, 2 to 3)
        val dirs = IceSlide.path(tile(0, 0), tile(4, 4), g)
        assertTrue(dirs != null)
        assertEquals(tile(4, 4), replay(tile(0, 0), dirs!!, g))
    }

    @Test
    fun `unreachable target returns null`() {
        val g = grid(5, 5, 2 to 2)
        // (2,2) is the pillar itself — a slide can never rest on it.
        assertNull(IceSlide.path(tile(0, 0), tile(2, 2), g))
    }
}
