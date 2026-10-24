package com.projectx.pathfinder.hops

import com.google.gson.JsonParser
import com.projectx.pathfinder.GridWorld
import com.projectx.pathfinder.GridWorld.Companion.tile
import com.projectx.pathfinder.HopModel
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HopLearningTest {

    @Test
    fun `a step and its reverse are one edge`() {
        val east = HopEdges.pack(3200, 3200, 0, Direction.EAST)
        assertEquals(east, HopEdges.pack(3201, 3200, 0, Direction.WEST))
        val key = HopEdges.key(HopEdges.PASS, east)
        assertEquals("pe:3200:3200:0:E", key)
        assertEquals(east, HopEdges.parse(key))
        assertEquals(HopEdges.pack(3200, 3200, 0, Direction.NORTH_EAST), HopEdges.pack(3201, 3201, 0, Direction.SOUTH_WEST))
        assertEquals(-1L, HopEdges.parse("pt:123:0"))
    }

    @Test
    fun `an edge is usable after enough successes and must recover after a failure`() {
        val e = HopEvidence()
        e.success(true, 1, "b")
        assertFalse(e.usableEdge())
        e.success(true, 1, "b")
        assertTrue(e.usableEdge())
        e.failure(2, "b")
        assertFalse(e.usableEdge())
        repeat(3) { e.success(true, 3, "b") }
        assertTrue(e.usableEdge())
    }

    @Test
    fun `evidence survives a store line round trip`() {
        val e = HopEvidence()
        e.success(true, 20000, "951")
        e.success(false, 20001, "951")
        e.contributors += "abc"
        val (key, back) = HopEvidence.fromJson(JsonParser.parseString(e.toJson("pe:1:2:0:N", "abc")).asJsonObject)!!
        assertEquals("pe:1:2:0:N", key)
        assertEquals(1, back.s)
        assertEquals(1, back.w)
        assertEquals(20001L, back.lastDay)
        assertTrue("abc" in back.contributors)
    }

    @Test
    fun `the same machine's evidence merges by the larger count, others' add up`() {
        val a = HopEvidence().apply { success(true, 1, "b") }
        val b = HopEvidence().apply { success(true, 1, "b") }
        assertEquals(1, HopKnowledge.atLeast(a, b).s)
        assertEquals(2, a.copy().also { it.add(b) }.s)
    }

    @Test
    fun `a learned edge opens the hop rule and a stopper closes it`() {
        val wall = GridWorld().block(3205, 3200)
        val edge = HopEdges.key(HopEdges.PASS, HopEdges.pack(3204, 3200, 0, Direction.EAST))
        val beyond = HopEdges.key(HopEdges.PASS, HopEdges.pack(3205, 3200, 0, Direction.EAST))
        val line = { key: String -> HopEvidence().apply { success(true, 1, "b"); success(true, 1, "b") }.toJson(key, "x") }
        val knowledge = HopKnowledge(listOf(line(edge), line(beyond)), null, "me")
        val rule = knowledge.rule(wall.rule) { true }
        assertEquals(tile(3210, 3200), HopModel.surge(rule, tile(3200, 3200), Direction.EAST))
        assertEquals(tile(3204, 3200), HopModel.surge(wall.rule, tile(3200, 3200), Direction.EAST))

        val stopper = HopEdges.key(HopEdges.STOP, HopEdges.pack(3202, 3200, 0, Direction.EAST))
        val stopped = HopKnowledge(listOf(line(stopper)), null, "me").rule(GridWorld().rule) { true }
        assertEquals(tile(3202, 3200), HopModel.surge(stopped, tile(3200, 3200), Direction.EAST))
    }

    @Test
    fun `a client that does not learn records nothing`() {
        val knowledge = HopKnowledge(emptyList(), null, "me")
        knowledge.record("pe:1:1:0:N") { it.success(true, 1, "b") }
        assertEquals(0, knowledge.size)
    }

    @Test
    fun `learned evidence is written to the store and read back`() {
        val dir = Files.createTempDirectory("hops")
        try {
            val knowledge = HopKnowledge(emptyList(), HopStore(dir), "me")
            val key = HopEdges.key(HopEdges.PASS, HopEdges.pack(3204, 3200, 0, Direction.EAST))
            knowledge.record(key) { it.success(true, 1, "b") }
            knowledge.record(key) { it.success(true, 1, "b") }
            knowledge.flush()
            assertTrue(Files.isRegularFile(dir.resolve("knowledge.jsonl")))
            val reloaded = HopKnowledge(emptyList(), HopStore(dir), "me")
            assertEquals(2, reloaded.evidence(key)!!.s)
            assertTrue(reloaded.passable(3205, 3200, 0, Direction.WEST))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun observation(world: GridWorld, from: Tile, direction: Direction, landed: Tile?): HopObservation {
        val o = HopObservation()
        o.ability = HopExplainer.SURGE
        o.from = from
        o.starts += from
        o.direction = direction
        o.capture(from, world.map(), null)
        o.landed = landed
        o.outcome = if (landed == null) HopExplainer.REFUSED else HopExplainer.LANDED
        return o
    }

    @Test
    fun `a surge that went as predicted teaches nothing`() {
        val world = GridWorld().block(3206, 3200)
        val result = HopExplainer.explain(observation(world, tile(3200, 3200), Direction.EAST, tile(3205, 3200)))
        assertEquals(HopExplainer.Kind.MATCH, result.kind)
    }

    @Test
    fun `a surge through a fence names the fence a hop-passable edge`() {
        val world = GridWorld().wall(3204, 3200, Direction.EAST)
        val result = HopExplainer.explain(observation(world, tile(3200, 3200), Direction.EAST, tile(3210, 3200)))
        assertEquals(HopExplainer.Kind.PASS, result.kind, result.toString())
        assertEquals(listOf(HopEdges.pack(3204, 3200, 0, Direction.EAST)), result.pass)
        assertTrue(result.strong)
    }

    @Test
    fun `a surge stopped on open ground names the next step a stopper`() {
        val result = HopExplainer.explain(observation(GridWorld(), tile(3200, 3200), Direction.EAST, tile(3206, 3200)))
        assertEquals(HopExplainer.Kind.STOP, result.kind, result.toString())
        assertEquals(listOf(HopEdges.pack(3206, 3200, 0, Direction.EAST)), result.stop)
    }

    @Test
    fun `an observation survives its json line`() {
        val world = GridWorld().wall(3204, 3200, Direction.EAST)
        val o = observation(world, tile(3200, 3200), Direction.EAST, tile(3210, 3200))
        val back = assertNotNull(HopObservation.fromJson(o.toJson()))
        assertEquals(o.landed, back.landed)
        assertEquals(o.direction, back.direction)
        assertFalse(back.walkStep(3204, 3200, 0, Direction.EAST))
        assertTrue(back.walkStep(3203, 3200, 0, Direction.EAST))
        assertEquals(HopExplainer.Kind.PASS, HopExplainer.explain(back).kind)
    }

    @Test
    fun `explanations become evidence`() {
        val world = GridWorld().wall(3204, 3200, Direction.EAST)
        val o = observation(world, tile(3200, 3200), Direction.EAST, tile(3210, 3200))
        val recorded = HashMap<String, HopEvidence>()
        HopRecorder.apply(o, HopExplainer.explain(o)) { key, change -> change(recorded.getOrPut(key) { HopEvidence() }) }
        assertEquals(1, recorded["pe:3204:3200:0:E"]?.s)
    }
}
