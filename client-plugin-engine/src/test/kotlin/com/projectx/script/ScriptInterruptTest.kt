package com.projectx.script

import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives scripts off the game thread by ticking them by hand, as the client's main-logic pulse would. */
class ScriptInterruptTest {

    private fun runUntil(script: Script, timeoutMillis: Long = 5000, done: (elapsedMillis: Long) -> Boolean) {
        val start = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - start < timeoutMillis) {
                if (done(System.currentTimeMillis() - start)) return
                script.tick()
                Thread.sleep(5)
            }
        } finally {
            script.stop()
        }
        error("script did not finish within $timeoutMillis ms")
    }

    @Test
    fun `interruptWhen cancels the block once the condition holds`() {
        var interrupt = false
        var result: String? = "unset"
        var blockFinished = false
        var passDone = false
        val script = object : Script() {
            override suspend fun loop() {
                if (passDone) return delay(1000)
                result = interruptWhen({ interrupt }) {
                    delay(10_000)
                    blockFinished = true
                    "finished"
                }
                passDone = true
            }
        }

        runUntil(script) { elapsed ->
            if (elapsed > 100) interrupt = true
            passDone
        }

        assertNull(result)
        assertFalse(blockFinished)
    }

    @Test
    fun `interruptWhen returns the block's result when nothing interrupts it`() {
        var result: String? = null
        val script = object : Script() {
            override suspend fun loop() {
                if (result != null) return delay(1000)
                result = interruptWhen({ false }) {
                    delay(60)
                    "finished"
                }
            }
        }

        runUntil(script) { result != null }

        assertEquals("finished", result)
    }

    @Test
    fun `a Kotlin script overriding shouldInterrupt has its pass cancelled`() {
        val script = object : Script() {
            var danger = false
            var passesStarted = 0
            var passesCompleted = 0

            override fun shouldInterrupt() = danger

            override suspend fun loop() {
                passesStarted++
                if (passesStarted >= 3) danger = false
                delay(10_000)
                passesCompleted++
            }
        }

        runUntil(script) { elapsed ->
            if (elapsed > 80 && script.passesStarted == 1) script.danger = true
            script.passesStarted >= 3
        }

        assertEquals(0, script.passesCompleted)
        assertTrue(script.passesStarted >= 3)
    }

    @Test
    fun `a Java Wait for a Kotlin helper hands its result to onResult`() {
        var received: String? = null
        var stepAfter = false
        val script = object : JavaScript() {
            override fun onLoop(): Wait = if (stepAfter) Wait.ms(1000) else Wait.sequence(
                { Wait.Call<String>({ delay(60); "helper result" }, Consumer { received = it }) },
                { stepAfter = true; null },
            )
        }

        runUntil(script) { stepAfter }

        assertEquals("helper result", received)
    }

    @Test
    fun `shouldInterrupt cuts a Java helper Wait short without calling onResult`() {
        var danger = false
        var called = false
        var loops = 0
        val script = object : JavaScript() {
            override fun shouldInterrupt() = danger

            override fun onLoop(): Wait {
                loops++
                if (loops > 1) {
                    danger = false
                    return Wait.ms(1000)
                }
                return Wait.Call<String>({ delay(10_000); "late" }, Consumer { called = true })
            }
        }

        runUntil(script) { elapsed ->
            if (elapsed > 80 && loops == 1) danger = true
            loops > 1
        }

        assertFalse(called)
    }
}
