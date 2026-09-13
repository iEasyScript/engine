package com.projectx.script

import com.projectx.script.api.localPlayer
import com.projectx.util.gaussian
import com.projectx.webwalker.WebWalker
import world.gregs.voidps.type.Tile

/**
 * Base class for scripts written in Java.
 *
 * Script bodies run on the client's main-logic tick, so a Java script must never block —
 * a `Thread.sleep` here would freeze the game, and Java cannot implement Kotlin's suspending
 * [Script.loop] correctly (the continuation it receives cannot be honoured, so every wait
 * would silently return immediately and the loop would spin at tick rate).
 *
 * [onLoop] therefore returns the [Wait] the engine should perform on the script's behalf,
 * and the engine does the suspending. Outcome-gated waits stay available: see [Wait.until].
 * Every Kotlin helper that waits has a [Wait] of the same name, so nothing in the API is Kotlin-only.
 */
abstract class JavaScript : Script() {

    /** One pass of the script. Return what the engine should wait for before the next pass. */
    abstract fun onLoop(): Wait

    /**
     * Called before every step of a [Wait.sequence] or [Wait.loop] runs. A step can start long after
     * [onLoop] returned, so refresh anything the steps read from the game here.
     */
    protected open fun beforeEachStep() {}

    private var interrupted = false

    final override suspend fun loop() {
        interrupted = false
        perform(onLoop())
    }

    private fun interruptRequested(): Boolean {
        if (!interrupted) interrupted = runCatching { shouldInterrupt() }.getOrDefault(false)
        return interrupted
    }

    /** Performs [wait]; false once a [Wait.abort] or [shouldInterrupt] has ended the steps it was part of. */
    private suspend fun perform(wait: Wait?): Boolean {
        when (wait) {
            null -> Unit
            Wait.Abort -> return false
            is Wait.Millis -> {
                val millis = if (wait.variance == 0) wait.mean else gaussian(wait.mean, wait.variance)
                val until = System.currentTimeMillis() + millis
                delayUntil(millis.toLong().coerceAtLeast(0), INTERRUPT_POLL_MILLIS) {
                    System.currentTimeMillis() >= until || interruptRequested()
                }
            }
            is Wait.Until -> delayUntil(wait.timeoutMillis, wait.pollMillis.coerceAtMost(INTERRUPT_POLL_MILLIS)) {
                wait.predicate.asBoolean || interruptRequested()
            }
            is Wait.While -> delayUntil(wait.timeoutMillis, INTERRUPT_POLL_MILLIS) {
                !wait.predicate.asBoolean || interruptRequested()
            }
            is Wait.XpDrop -> waitForXPDrop(wait.skill, wait.timeoutMillis)
            is Wait.Idle -> {
                var idleChecks = 0
                var nextCheck = System.currentTimeMillis() + TICK_MILLIS
                delayUntil(wait.maxTicks.toLong() * TICK_MILLIS, INTERRUPT_POLL_MILLIS) {
                    if (System.currentTimeMillis() >= nextCheck) {
                        nextCheck += TICK_MILLIS
                        val busy = localPlayer.isMoving || (wait.countAnimation && localPlayer.isAnimating)
                        idleChecks = if (busy) 0 else idleChecks + 1
                    }
                    idleChecks >= wait.idleChecks || interruptRequested()
                }
            }
            is Wait.Sequence -> for (step in wait.steps) {
                if (stopped) return true
                if (interruptRequested()) return false
                beforeEachStep()
                if (!perform(step.run())) return false
            }
            is Wait.Loop -> while (!stopped) {
                if (interruptRequested()) return false
                beforeEachStep()
                val next = wait.step.run() ?: break
                if (!perform(next)) return false
            }
            is Wait.WebWalk -> {
                val result = WebWalker.walk(this, Tile.of(wait.x, wait.y, wait.plane), wait.arriveDistance, wait.useLodestones)
                wait.onResult?.accept(result)
            }
            is Wait.Call<*> -> performCall(wait)
        }
        return !interrupted
    }

    private suspend fun <T> performCall(wait: Wait.Call<T>) {
        // Boxed so a helper that legitimately returns null is not mistaken for an interrupted one.
        val outcome = interruptWhen({ interruptRequested() }) { Outcome(wait.action(this)) } ?: return
        wait.onResult?.accept(outcome.value)
    }

    private class Outcome<T>(val value: T)

    companion object {
        const val INTERRUPT_POLL_MILLIS = Script.INTERRUPT_POLL_MILLIS
    }
}

/** One step of a [Wait.sequence]: act, then return what to wait for before the next step, or null for nothing. */
fun interface Step {
    fun run(): Wait?
}
