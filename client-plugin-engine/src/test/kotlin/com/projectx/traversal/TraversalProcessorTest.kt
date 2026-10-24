package com.projectx.traversal

import com.projectx.script.Script
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import kotlinx.coroutines.runBlocking
import world.gregs.voidps.type.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TraversalProcessorTest {

    class TestScript : StateMachineScript<TestScript>() {
        var done = false
        var interrupted = false

        override fun getStartState(): State<TestScript> = Idle()
    }

    class Idle : State<TestScript>() {
        override suspend fun TestScript.checkNext(): State<TestScript>? = null
        override suspend fun TestScript.stateLoop() {}
    }

    private class Step(
        val name: String,
        var isReached: Boolean = false,
        var working: Boolean = true,
        val onProcess: Step.() -> Unit = {},
    ) : TraversalNode() {
        var processed = 0

        override suspend fun process(script: Script): Boolean {
            processed++
            onProcess()
            return working
        }

        override fun reached(script: Script): Boolean = isReached

        override fun copy(): TraversalNode = this

        fun fail(reason: String) = failTraversal(reason)

        fun repath() = requestTraversalRepath()

        override fun toString() = name
    }

    private fun list(vararg nodes: TraversalNode): TraversalNodeList =
        TraversalNodeList(nodes.first()).also { list -> nodes.drop(1).forEach { list.add(it) } }

    private fun processor(nodes: TraversalNodeList, interrupt: (TestScript.() -> Boolean)? = null, timeout: Long = 0) =
        TraversalProcessor<TestScript>({ done }, interrupt, nodes, timeout).also { it.playerTile = { Tile.of(3200, 3200, 0) } }

    @Test
    fun `the finished condition stops it having arrived`() = runBlocking {
        val script = TestScript()
        val p = processor(list(Step("a")))
        assertTrue(p.process(script))
        script.done = true
        assertFalse(p.process(script))
        assertEquals(TraversalOutcome.FINISHED, p.outcome)
        assertTrue(p.outcome.isArrival)
    }

    @Test
    fun `the interrupt condition stops it cut short`() = runBlocking {
        val script = TestScript()
        val p = processor(list(Step("a")), interrupt = { interrupted })
        script.interrupted = true
        assertFalse(p.process(script))
        assertTrue(p.wasInterrupted)
        assertFalse(p.outcome.isArrival)
    }

    @Test
    fun `several reached nodes are passed in one go`() = runBlocking {
        val last = Step("c")
        val p = processor(list(Step("a", isReached = true), Step("b", isReached = true), last))
        assertTrue(p.process(TestScript()))
        assertSame(last, p.currentNode)
        assertEquals(1, last.processed)
    }

    @Test
    fun `a last node that is reached completes and one that gives up stalls`() = runBlocking {
        val done = processor(list(Step("a", isReached = true)))
        assertFalse(done.process(TestScript()))
        assertEquals(TraversalOutcome.COMPLETED, done.outcome)

        val stuck = processor(list(Step("a", working = false)))
        assertFalse(stuck.process(TestScript()))
        assertEquals(TraversalOutcome.STALLED, stuck.outcome)
    }

    @Test
    fun `a node that cannot complete fails the traversal`() = runBlocking {
        val p = processor(list(Step("door") { fail("locked") }, Step("after")))
        assertFalse(p.process(TestScript()))
        assertEquals(TraversalOutcome.FAILED, p.outcome)
    }

    @Test
    fun `a repath is refused without a supplier and taken with one`() = runBlocking {
        var accepted = false
        val p = processor(list(Step("hop") { accepted = repath() }))
        assertTrue(p.process(TestScript()))
        assertFalse(accepted)

        val supported = processor(list(Step("hop") { accepted = repath() })).also { it.repathSupported = true }
        assertFalse(supported.process(TestScript()))
        assertTrue(accepted)
        assertEquals(TraversalOutcome.REPATH_REQUESTED, supported.outcome)
    }

    @Test
    fun `standing still past the timeout times out`() = runBlocking {
        val p = processor(list(Step("a")), timeout = 1)
        assertTrue(p.process(TestScript()))
        Thread.sleep(5)
        assertFalse(p.process(TestScript()))
        assertEquals(TraversalOutcome.TIMED_OUT, p.outcome)
    }

    @Test
    fun `nodes see a stop that has fired`() = runBlocking {
        val script = TestScript()
        var seen = false
        val node = object : TraversalNode() {
            override suspend fun process(script: Script): Boolean {
                (script as TestScript).done = true
                Thread.sleep(350)
                seen = traversalStopRequested()
                return true
            }

            override fun reached(script: Script) = false
            override fun copy(): TraversalNode = this
        }
        val p = processor(list(node))
        p.process(script)
        assertTrue(seen)
    }
}
