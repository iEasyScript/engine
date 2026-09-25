package com.projectx.script.api

import java.util.function.BooleanSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotationManagerTest {
    private var tick = 100L
    private var millis = 50_000L
    private val fired = mutableListOf<String>()

    private fun manager(loop: Boolean = false) = RotationManager(loop).also {
        it.currentTick = { tick }
        it.currentMillis = { millis }
    }

    private fun step(label: String, result: Boolean = true) =
        RotationStep.custom(label, BooleanSupplier { fired += label; result })

    @Test
    fun `steps fire in order, each after the previous step's tick wait`() {
        val rotation = manager()
        rotation.load(listOf(step("a").waitTicks(2), step("b"), step("c")))

        assertTrue(rotation.execute())
        assertFalse(rotation.execute())
        tick += 1
        assertFalse(rotation.execute())
        tick += 1
        assertTrue(rotation.execute())
        tick += 2
        assertFalse(rotation.execute())
        tick += 1
        assertTrue(rotation.execute())

        assertEquals(listOf("a", "b", "c"), fired)
        assertTrue(rotation.isFinished)
    }

    @Test
    fun `a failed action still costs its wait, so a missing ability never stalls the rotation`() {
        val rotation = manager()
        rotation.load(listOf(step("missing", result = false).waitTicks(1), step("next")))

        assertTrue(rotation.execute())
        assertFalse(rotation.recentSteps.first().succeeded)
        tick += 1
        assertTrue(rotation.execute())
        assertEquals(listOf("missing", "next"), fired)
    }

    @Test
    fun `an unmet condition with no replacement skips the step without waiting`() {
        val rotation = manager()
        rotation.load(listOf(step("guarded").onlyIf { false }, step("next")))

        assertFalse(rotation.execute())
        assertTrue(rotation.execute())
        assertEquals(listOf("next"), fired)
    }

    @Test
    fun `an unmet condition runs the replacement and waits the replacement wait`() {
        val rotation = manager()
        rotation.load(
            listOf(
                step("primary").onlyIf { false }.otherwise("fallback", BooleanSupplier { fired += "fallback"; true }, waitTicks = 1),
                step("next"),
            ),
        )

        assertTrue(rotation.execute())
        assertEquals("fallback", rotation.recentSteps.first().label)
        tick += 1
        assertTrue(rotation.execute())
        assertEquals(listOf("fallback", "next"), fired)
    }

    @Test
    fun `a condition that throws skips the step`() {
        val rotation = manager()
        rotation.load(listOf(step("broken").onlyIf { error("boom") }, step("next")))

        assertFalse(rotation.execute())
        assertTrue(rotation.execute())
        assertEquals(listOf("next"), fired)
    }

    @Test
    fun `millisecond waits count wall time, not ticks`() {
        val rotation = manager()
        rotation.load(listOf(step("a").waitMillis(900), step("b")))

        rotation.execute()
        tick += 5
        millis += 899
        assertFalse(rotation.execute())
        millis += 1
        assertTrue(rotation.execute())
    }

    @Test
    fun `a looping rotation starts over once it runs out`() {
        val rotation = manager(loop = true)
        rotation.load(listOf(step("a").waitTicks(1), step("b").waitTicks(1)))

        repeat(4) {
            rotation.execute()
            tick += 1
        }
        assertEquals(listOf("a", "b", "a", "b"), fired)
    }

    @Test
    fun `reloading the running rotation keeps its place, a new one starts from the top`() {
        val rotation = manager()
        val first = listOf(step("a").waitTicks(0), step("b").waitTicks(0))
        rotation.load(first)
        rotation.execute()

        rotation.load(first)
        assertEquals(1, rotation.index)

        rotation.load(listOf(step("c")))
        assertEquals(0, rotation.index)
    }

    @Test
    fun `jumping to a label fires that step next, immediately`() {
        val rotation = manager()
        rotation.load(listOf(step("a").waitTicks(5), step("b"), step("c")))
        rotation.execute()

        assertTrue(rotation.jumpTo("c"))
        assertTrue(rotation.execute())
        assertEquals(listOf("a", "c"), fired)
    }
}
