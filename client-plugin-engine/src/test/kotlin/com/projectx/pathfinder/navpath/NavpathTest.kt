package com.projectx.pathfinder.navpath

import com.google.gson.JsonParser
import com.projectx.script.api.Lodestone
import com.projectx.webwalker.WebInterfaceStep
import com.projectx.webwalker.WebLinkKind
import com.projectx.webwalker.WebPath
import world.gregs.voidps.type.Tile
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NavpathTest {

    private fun fixture(name: String): String =
        NavpathTest::class.java.getResourceAsStream("/navpath/$name")!!.use { it.reader().readText() }

    private fun tile(x: Int, y: Int, plane: Int = 0) = Tile.of(x, y, plane)

    private fun mapped(name: String, start: Tile): WebPath {
        val result = NavRouteMapper.map(start, NavResultsParser.parse(fixture(name)))
        return assertIs<NavRouteMapper.Mapped.Route>(result, result.toString()).path
    }

    // ---- parser ----

    @Test
    fun `a walk with a door, a surge and a staircase parses into its steps`() {
        val route = NavResultsParser.parse(fixture("walk-door-stairs.json"))
        assertTrue(route.found)
        assertEquals(6, route.lengthTiles)
        assertEquals(listOf("move", "move", "door", "surge", "move", "object", "move"), route.steps.map { it.type })
        val door = assertIs<NavStep.Door>(route.steps[2])
        assertEquals("IN", door.direction)
        assertEquals(1530, door.realIdClosed)
        assertEquals(tile(3201, 3200), door.tileInside)
        val stairs = assertIs<NavStep.Loc>(route.steps[5])
        assertEquals(24350, stairs.id)
        assertEquals("Climb-up", stairs.action)
        assertEquals(NavArea(3211, 3200, 3214, 3203, 1), stairs.destination)
        assertEquals(tile(3212, 3201, 1), stairs.to)
    }

    @Test
    fun `chained rows become the step's chain`() {
        val route = NavResultsParser.parse(fixture("teleport-npc-item.json"))
        val lodestone = assertIs<NavStep.Lodestone>(route.steps[0])
        assertEquals("VARROCK", lodestone.name)
        val npc = assertIs<NavStep.Npc>(route.steps[2])
        assertEquals(376, npc.id)
        assertEquals("Travel", npc.action)
        val click = assertIs<NavStep.IFSlot>(npc.chain.single())
        assertEquals(1188, click.interfaceId)
        assertEquals(0, click.clickId)
        val glory = assertIs<NavStep.ItemClick>(route.steps[3])
        assertEquals("Rub", glory.option)
        assertEquals(720, assertIs<NavStep.IFSlot>(glory.chain.single()).interfaceId)
    }

    @Test
    fun `fairy rings keep the ring object or the portable flag`() {
        val route = NavResultsParser.parse(fixture("fairy-rings.json"))
        val portable = assertIs<NavStep.FairyRing>(route.steps[0])
        assertTrue(portable.portable)
        assertEquals(-1, portable.objectId)
        val ring = assertIs<NavStep.FairyRing>(route.steps[2])
        assertFalse(ring.portable)
        assertEquals(12128, ring.objectId)
        assertEquals("ajr", ring.code)
    }

    @Test
    fun `a generic teleport is kept as unsupported and a missing route as not found`() {
        assertIs<NavStep.Unsupported>(NavResultsParser.parse(fixture("generic-teleport.json")).steps[1])
        val missing = NavResultsParser.parse(fixture("not-found.json"))
        assertFalse(missing.found)
        assertTrue(missing.steps.isEmpty())
        assertTrue(NavResultsParser.parse(fixture("off-graph-start.json")).enteredByTeleport)
    }

    @Test
    fun `malformed answers are refused`() {
        assertFailsWith<NavParseException> { NavResultsParser.parse("") }
        assertFailsWith<NavParseException> { NavResultsParser.parse("<html>busy</html>") }
        assertFailsWith<NavParseException> { NavResultsParser.parse("""{"found":true}""") }
        assertFailsWith<NavParseException> { NavResultsParser.parse("""{"actions":[{"type":"move","to":[1,2]}]}""") }
        assertFailsWith<NavParseException> { NavResultsParser.parse("""{"actions":[{"type":"npc","to":[1,2,0]}]}""") }
    }

    // ---- mapping onto the walker ----

    @Test
    fun `moves and surges become walked tiles and a door becomes a door link`() {
        val path = mapped("walk-door-stairs.json", tile(3198, 3200))
        assertEquals(tile(3198, 3200), path.tile(0))
        val door = path.nextLink(1)
        val doorLink = assertNotNull(path.linkAt(door))
        assertEquals(WebLinkKind.DOOR, doorLink.kind)
        assertEquals(1530, doorLink.objectId)
        assertEquals("Open", doorLink.action)
        assertEquals(tile(3200, 3200), path.tile(door - 1))
        assertEquals(tile(3201, 3200), path.tile(door))
        for (i in door + 1 until path.size) {
            if (path.linkAt(i) == null) assertTrue(path.tile(i).getLongestDelta(path.tile(i - 1)) <= 1, "a gap at $i")
        }
        val stairs = path.nextLink(door + 1)
        val stairsLink = assertNotNull(path.linkAt(stairs))
        assertEquals(WebLinkKind.OBJECT, stairsLink.kind)
        assertEquals(24350, stairsLink.objectId)
        assertEquals(12, stairsLink.searchRadius)
        assertEquals(1, stairsLink.to.plane)
        assertTrue(stairsLink.to.contains(3212, 3201, 1))
        assertEquals(tile(3213, 3202, 1), path.tile(path.lastIndex))
    }

    @Test
    fun `teleports, npcs and items become links with their chains`() {
        val path = mapped("teleport-npc-item.json", tile(3000, 3000))
        val lodestone = assertNotNull(path.linkAt(1))
        assertEquals(WebLinkKind.LODESTONE, lodestone.kind)
        assertEquals(Lodestone.VARROCK, lodestone.lodestone)
        val npc = assertNotNull(path.linkAt(path.nextLink(2)))
        assertEquals(WebLinkKind.NPC, npc.kind)
        assertEquals(376, npc.target?.id)
        assertEquals(WebInterfaceStep(1188, 8, -1, 0), npc.chain.single())
        val glory = assertNotNull(path.linkAt(path.lastIndex))
        assertEquals(WebLinkKind.ITEM, glory.kind)
        assertEquals("Rub", glory.action)
        assertTrue(glory.isGlobal)
        assertTrue(glory.to.contains(3087, 3496, 0))
    }

    @Test
    fun `passage jewellery, item-on-object and interface steps map onto their link kinds`() {
        val path = mapped("poa-useon-ifslot.json", tile(3000, 3000))
        val links = (0 until path.size).mapNotNull(path::linkAt)
        assertEquals(listOf(WebLinkKind.POA, WebLinkKind.USE_ON, WebLinkKind.INTERFACE), links.map { it.kind })
        assertEquals(listOf("Games necklace", "Burthorpe"), links[0].menu)
        assertEquals(2622, links[1].objectId)
        assertEquals(954, links[1].target?.id)
        assertEquals(WebInterfaceStep(1092, 21, -1, 1), links[2].chain.first())
        assertEquals(tile(3301, 3300), path.tile(path.lastIndex))
    }

    @Test
    fun `fairy rings dial from the ring or from the portable ring`() {
        val path = mapped("fairy-rings.json", tile(3000, 3000))
        val links = (0 until path.size).mapNotNull(path::linkAt)
        assertTrue(links[0].portable)
        assertEquals("aip", links[0].fairyCode)
        assertFalse(links[1].portable)
        assertEquals(12128, links[1].objectId)
        assertEquals("Configure", links[1].action)
        assertEquals("ajr", links[1].fairyCode)
    }

    @Test
    fun `routes the walker cannot take are refused whole`() {
        assertIs<NavRouteMapper.Mapped.Refused>(NavRouteMapper.map(tile(3200, 3200), NavResultsParser.parse(fixture("generic-teleport.json"))))
        assertIs<NavRouteMapper.Mapped.Refused>(NavRouteMapper.map(tile(3000, 3000), NavResultsParser.parse(fixture("off-graph-start.json"))))
        assertIs<NavRouteMapper.Mapped.Refused>(NavRouteMapper.map(tile(3000, 3000), NavResultsParser.parse(fixture("not-found.json"))))
    }

    // ---- the request ----

    @Test
    fun `the request carries the profile, abilities and seed`() {
        val request = NavRequest(tile(3200, 3200), tile(3300, 3300, 1), listOf("coins" to 100, "varbit_39" to 1), true, 2, false, 1200, 77)
        val body = JsonParser.parseString(request.toJson()).asJsonObject
        assertEquals(3300, body.getAsJsonObject("goal").get("wx").asInt)
        assertEquals(1, body.getAsJsonObject("goal").get("plane").asInt)
        val requirements = body.getAsJsonObject("profile").getAsJsonArray("requirements")
        assertEquals("coins", requirements[0].asJsonObject.get("key").asString)
        assertEquals(100, requirements[0].asJsonObject.get("value").asInt)
        assertTrue(body.getAsJsonObject("options").get("only_actions").asBoolean)
        assertEquals(2, body.getAsJsonObject("surge").get("charges").asInt)
        assertEquals(1200, body.getAsJsonObject("dive").get("available_in_ms").asInt)
        assertEquals(77, body.get("seed").asInt)
    }

    // ---- fallback, retry and breaker ----

    private class FakeTransport(vararg replies: () -> NavReply) : NavTransport {
        val queue = ArrayDeque(replies.toList())
        var calls = 0
        var lastToken: String? = null
        var lastUrl: String? = null

        override fun post(url: String, token: String, body: String): NavReply {
            calls++
            lastToken = token
            lastUrl = url
            return (queue.removeFirstOrNull() ?: { NavReply(200, "") })()
        }
    }

    private val request = NavRequest(Tile.of(3198, 3200, 0), Tile.of(3213, 3202, 1), emptyList(), false, 1, false, 0, 1)

    private fun planner(transport: NavTransport, now: () -> Long = { 0L }, token: String? = "secret") =
        HostedPlanner(transport, { "https://example.test/api/navpath/" }, { token }, now, pause = {}, log = {})

    @Test
    fun `a good answer becomes a route, sent with the bearer token`() {
        val transport = FakeTransport({ NavReply(200, fixture("walk-door-stairs.json")) })
        val outcome = planner(transport).plan(request, true)
        assertIs<HostedPlanner.Outcome.Planned>(outcome)
        assertEquals("secret", transport.lastToken)
        assertEquals("https://example.test/api/navpath/route", transport.lastUrl)
    }

    @Test
    fun `a busy server is asked again, then given up on`() {
        val busyThenGood = FakeTransport({ NavReply(503, "busy") }, { NavReply(200, fixture("walk-door-stairs.json")) })
        assertIs<HostedPlanner.Outcome.Planned>(planner(busyThenGood).plan(request, true))
        assertEquals(2, busyThenGood.calls)

        val alwaysBusy = FakeTransport({ NavReply(503, "busy") }, { NavReply(503, "busy") }, { NavReply(503, "busy") })
        assertIs<HostedPlanner.Outcome.Fallback>(planner(alwaysBusy).plan(request, true))
        assertEquals(1 + HostedPlanner.BUSY_RETRIES, alwaysBusy.calls)
    }

    @Test
    fun `a refused token falls back at once and rests the server`() {
        var now = 0L
        val transport = FakeTransport({ NavReply(401, "") })
        val p = planner(transport, { now })
        assertIs<HostedPlanner.Outcome.Fallback>(p.plan(request, true))
        assertTrue(p.resting())
        assertIs<HostedPlanner.Outcome.Fallback>(p.plan(request, true))
        assertEquals(1, transport.calls)
        now = HostedPlanner.AUTH_REST_MS + 1
        assertFalse(p.resting())
    }

    @Test
    fun `no token never calls the server`() {
        val transport = FakeTransport()
        assertIs<HostedPlanner.Outcome.Fallback>(planner(transport, token = null).plan(request, true))
        assertEquals(0, transport.calls)
    }

    @Test
    fun `repeated failures open the breaker for a minute`() {
        var now = 0L
        val fail: () -> NavReply = { throw HttpTimeoutException("slow") }
        val transport = FakeTransport(fail, { throw IOException("reset") }, { NavReply(502, "") }, { NavReply(200, fixture("walk-door-stairs.json")) })
        val p = planner(transport, { now })
        repeat(HostedPlanner.BREAKER_FAILURES) { assertIs<HostedPlanner.Outcome.Fallback>(p.plan(request, true)) }
        assertTrue(p.resting())
        assertIs<HostedPlanner.Outcome.Fallback>(p.plan(request, true))
        assertEquals(HostedPlanner.BREAKER_FAILURES, transport.calls)
        now = HostedPlanner.BREAKER_MS + 1
        assertIs<HostedPlanner.Outcome.Planned>(p.plan(request, true))
    }

    @Test
    fun `a garbled or unwalkable answer falls back`() {
        assertIs<HostedPlanner.Outcome.Fallback>(planner(FakeTransport({ NavReply(200, "{oops") })).plan(request, true))
        assertIs<HostedPlanner.Outcome.Fallback>(planner(FakeTransport({ NavReply(200, fixture("generic-teleport.json")) })).plan(request, true))
        assertIs<HostedPlanner.Outcome.Fallback>(planner(FakeTransport({ NavReply(400, "goal tile not found in snapshot") })).plan(request, true))
    }

    @Test
    fun `a teleport is refused when the walk may not use one`() {
        val transport = FakeTransport({ NavReply(200, fixture("teleport-npc-item.json")) })
        val outcome = planner(transport).plan(request.copy(start = Tile.of(3000, 3000, 0)), acceptsTeleports = false)
        assertIs<HostedPlanner.Outcome.Fallback>(outcome)
    }
}
