package com.projectx.webwalker

import com.projectx.script.api.Lodestone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
            assertTrue(link.cost > 0, "$link is free")
            assertTrue(link.searchRadius in 1..64, "$link has an absurd search radius")
            assertTrue(link.to.maxX >= link.to.minX && link.to.maxY >= link.to.minY, "$link has an inverted destination")
            assertTrue(link.to.plane in 0..3, "$link lands off the four planes")
            if (!link.isGlobal) {
                assertTrue(link.from.maxX >= link.from.minX && link.from.maxY >= link.from.minY, "$link has an inverted origin")
                assertTrue(link.from.plane in 0..3, "$link starts off the four planes")
            }
            when (link.kind) {
                WebLinkKind.OBJECT, WebLinkKind.DOOR, WebLinkKind.FAIRY_RING -> {
                    assertTrue(link.objectId >= 0, "$link has no object id")
                    assertTrue(link.action.isNotBlank(), "$link has no action")
                }
                WebLinkKind.USE_ON -> {
                    assertTrue(link.objectId >= 0, "$link has no object to use the item on")
                    assertTrue(link.target != null, "$link has no item")
                }
                WebLinkKind.NPC, WebLinkKind.ITEM, WebLinkKind.POA -> {
                    val target = link.target
                    assertTrue(target != null && (target.id >= 0 || !target.name.isNullOrBlank()), "$link does not say what it uses")
                    assertTrue(link.action.isNotBlank(), "$link has no action")
                }
                WebLinkKind.INTERFACE -> assertTrue(link.chain.firstOrNull() is WebInterfaceStep, "$link has no button to click")
                WebLinkKind.LODESTONE -> assertTrue(link.lodestone != null, "$link names no lodestone")
            }
        }
    }

    @Test
    fun `teleports are kept apart from the places links start`() {
        val teleports = WebLinks.globals
        assertTrue(teleports.isNotEmpty(), "no teleports loaded")
        assertTrue(teleports.all { it.isGlobal }, "a place-bound link was filed as a teleport")
        val local = WebLinks.all.filterNot { it.isGlobal }
        assertTrue(local.none { it in teleports }, "a teleport was also filed by place")
        assertEquals(WebLinks.all.size, local.size + teleports.size)
    }

    @Test
    fun `every lodestone is a teleport, Burthorpe and Wendlewick included`() {
        val reached = WebLinks.globals.filter { it.kind == WebLinkKind.LODESTONE }.mapNotNull { it.lodestone }.toSet()
        assertEquals(Lodestone.entries.toSet(), reached)
    }

    @Test
    fun `a fairy ring reaches every other ring`() {
        val rings = WebLinks.all.filter { it.kind == WebLinkKind.FAIRY_RING }
        assertTrue(rings.isNotEmpty(), "no fairy rings loaded")
        val codes = rings.mapNotNull { it.fairyCode }.toSet()
        val fromOne = rings.filter { it.from == rings.first().from }
        assertEquals(codes.size - 1, fromOne.size, "a ring does not reach every other")
        assertTrue(codes.all { it.length == 3 }, "a fairy ring code is not three letters")
    }

    @Test
    fun `a chain can use another object after its first`() {
        val trapdoor = WebLinks.all.first { it.kind == WebLinkKind.DOOR && it.action == "Lockpick" && it.chain.isNotEmpty() }
        assertEquals(WebObjectStep(5492, "Climb down", 20), trapdoor.chain.single())
        assertTrue(trapdoor.steps.isEmpty(), "an object step was listed among the interface clicks")
        assertTrue(trapdoor.to.minY > 9000, "the trapdoor should lead underground")
    }

    @Test
    fun `NPC and item links say what they use`() {
        val boat = WebLinks.all.first { it.kind == WebLinkKind.NPC && it.target?.name == "Captain Tobias" }
        assertTrue(boat.target!!.matches(-1, "captain tobias"), "an NPC is not matched by name, ignoring case")
        val passage = WebLinks.globals.first { it.kind == WebLinkKind.POA }
        assertEquals(2, passage.menu.size, "the Passage of the Abyss should pick a jewellery and then a place")
        val necklace = WebLinks.globals.first { it.kind == WebLinkKind.ITEM && it.target?.name?.startsWith("Games") == true }
        assertTrue(necklace.target!!.matches(3853, "Games necklace (8)"), "an item is not matched by the start of its name")
    }

    @Test
    fun `two teleports that differ only by what they use are different links`() {
        val base = WebLink(WebLinkKind.ITEM, WebArea(0, 0, 0, 0, -1), WebArea(1, 1, 1, 1, 0), 1000, "Rub", -1, 1, emptyList())
        val a = base.copy().using(WebTarget(1))
        val b = base.copy().using(WebTarget(2))
        assertNotEquals(a, b)
        assertEquals(WebLink(WebLinkKind.ITEM, WebArea(0, 0, 0, 0, -1), WebArea(1, 1, 1, 1, 0), 1000, "Rub", -1, 1, emptyList()).using(WebTarget(1)), a)
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
    fun `a link can carry the interface clicks that finish it`() {
        val base = WebLinks.all.first()
        val plain = WebLink(
            kind = base.kind, from = base.from, to = base.to, cost = base.cost,
            action = base.action, objectId = base.objectId,
            searchRadius = base.searchRadius, requirements = base.requirements,
        )
        assertEquals(emptyList<WebInterfaceStep>(), plain.steps, "a link has no interface clicks unless given some")

        val viaPanel = plain.clicking(
            WebInterfaceStep(1500, 20),
            WebInterfaceStep(1500, 22, slot = 3, option = 2),
        )
        assertEquals(2, viaPanel.steps.size)
        assertEquals(WebInterfaceStep(1500, 20, slot = -1, option = 1), viaPanel.steps.first())
        // The order is the order they happen in, so it must survive.
        assertEquals(3, viaPanel.steps[1].slot)
        assertEquals(2, viaPanel.steps[1].option)
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
