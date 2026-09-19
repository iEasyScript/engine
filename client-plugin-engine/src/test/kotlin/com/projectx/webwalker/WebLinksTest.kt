package com.projectx.webwalker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The link set is shipped as a resource and parsed by hand, so a bad edit or a lost file would otherwise only
 * show up as the walker quietly refusing to use stairs. None of this needs a game cache.
 */
class WebLinksTest {

    @Test
    fun `the shipped link set loads`() {
        assertTrue(WebLinks.size > 500, "only ${WebLinks.size} links loaded; is /webwalker/links.json present?")
        assertTrue(WebLinks.all.any { it.kind == WebLinkKind.OBJECT }, "no object links")
        assertTrue(WebLinks.all.any { it.kind == WebLinkKind.DOOR }, "no door links")
    }

    @Test
    fun `every link is usable`() {
        for (link in WebLinks.all) {
            assertTrue(link.objectId >= 0, "$link has no object id")
            assertTrue(link.action.isNotBlank(), "$link has no action")
            assertTrue(link.cost > 0, "$link is free")
            assertTrue(link.searchRadius in 1..64, "$link has an absurd search radius")
            assertTrue(link.from.maxX >= link.from.minX && link.from.maxY >= link.from.minY, "$link has an inverted origin")
            assertTrue(link.to.maxX >= link.to.minX && link.to.maxY >= link.to.minY, "$link has an inverted destination")
            assertTrue(link.from.plane in 0..3 && link.to.plane in 0..3, "$link leaves the four planes")
        }
    }

    @Test
    fun `links are indexed under every tile of their origin`() {
        val link = WebLinks.all.first { it.from.maxX > it.from.minX }
        for (x in link.from.minX..link.from.maxX) {
            for (y in link.from.minY..link.from.maxY) {
                assertTrue(
                    WebLinks.from(x, y, link.from.plane).contains(link),
                    "$link is not indexed at $x,$y",
                )
            }
        }
    }

    @Test
    fun `a tile with no link returns an empty list`() {
        assertEquals(emptyList(), WebLinks.from(1, 1, 0))
    }

    @Test
    fun `some links change plane, which is the point of having them`() {
        val crossPlane = WebLinks.all.count { it.from.plane != it.to.plane }
        assertTrue(crossPlane > 50, "only $crossPlane links change plane")
    }

    @Test
    fun `tile keys survive a round trip at the edges of the world`() {
        for (plane in 0..3) {
            for ((x, y) in listOf(0 to 0, 3222 to 3218, 16383 to 16383, 6400 to 12000)) {
                val key = WebPathfinder.key(x, y, plane)
                assertTrue(key >= 0, "key for $x,$y,$plane went negative")
                assertEquals(x, WebPathfinder.tileX(key))
                assertEquals(y, WebPathfinder.tileY(key))
                assertEquals(plane, WebPathfinder.tilePlane(key))
            }
        }
    }
}
