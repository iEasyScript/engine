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
    fun `a script can teach the walker a link it found`() {
        // Somewhere the shipped set has nothing, so the test cannot collide with real data.
        val x = 12_345
        val y = 11_111
        assertTrue(WebLinks.from(x, y, 0).isEmpty(), "the shipped set already has a link at $x,$y")

        assertTrue(WebLinks.registerObjectLink(x, y, 0, x, y + 4, 1, objectId = 4711, action = "Climb-up"))

        val learned = WebLinks.from(x, y, 0)
        assertEquals(1, learned.size)
        assertEquals(4711, learned[0].objectId)
        assertEquals("Climb-up", learned[0].action)
        assertEquals(1, learned[0].to.plane, "the link should reach the floor above")

        // Registering the same thing twice must not double it up.
        assertTrue(!WebLinks.registerObjectLink(x, y, 0, x, y + 4, 1, objectId = 4711, action = "Climb-up"))
        assertEquals(1, WebLinks.from(x, y, 0).size)
    }

    @Test
    fun `an inverted link is refused`() {
        val bad = WebLink(
            kind = WebLinkKind.OBJECT,
            from = WebArea(100, 90, 100, 100, 0),
            to = WebArea(200, 200, 200, 200, 0),
            cost = 1000,
            action = "Climb-up",
            objectId = 1,
            searchRadius = 8,
            requirements = emptyList(),
        )
        assertTrue(!WebLinks.register(bad), "an inverted origin should be refused")
    }

    @Test
    fun `a link whose object asks where to go carries the answer`() {
        // Kharid-et's fort entrance is the one that does this, and it offers two places.
        val entrance = WebLinks.all.filter { it.objectId == 116920 }
        assertEquals(2, entrance.size, "expected both fort entrance destinations")
        assertTrue(entrance.all { it.action == "Enter" })
        assertEquals(
            setOf("Main fortress", "Prison block"),
            entrance.mapNotNull { it.choice }.toSet(),
        )
        // The two answers must lead somewhere different, or one of them is pointless.
        assertEquals(2, entrance.map { it.to }.distinct().size)
    }

    @Test
    fun `a link with no choice leaves it unset`() {
        val plain = WebLinks.all.first { it.objectId != 116920 }
        assertEquals(null, plain.choice)
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
