package com.projectx.script

import com.projectx.script.api.localPlayer
import org.projectx.core.game.skill.Skill
import java.util.concurrent.ThreadLocalRandom
import java.util.function.BooleanSupplier
import kotlin.math.roundToInt

private const val TICK_MILLIS = 600

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
 */
abstract class JavaScript : Script() {

    /** One pass of the script. Return what the engine should wait for before the next pass. */
    abstract fun onLoop(): Wait

    /**
     * Called before every step of a [Wait.sequence] or [Wait.loop] runs. A step can start long after
     * [onLoop] returned, so refresh anything the steps read from the game here.
     */
    protected open fun beforeEachStep() {}

    final override suspend fun loop() {
        perform(onLoop())
    }

    /** Performs [wait]; false once a [Wait.abort] has ended the steps it was part of. */
    private suspend fun perform(wait: Wait?): Boolean {
        when (wait) {
            null -> Unit
            Wait.Abort -> return false
            is Wait.Millis -> delay(wait.mean, wait.variance)
            is Wait.Until -> delayUntil(wait.timeoutMillis, wait.pollMillis) { wait.predicate.asBoolean }
            is Wait.While -> delayWhile(wait.timeoutMillis) { wait.predicate.asBoolean }
            is Wait.XpDrop -> waitForXPDrop(wait.skill, wait.timeoutMillis)
            is Wait.Idle -> {
                var idleChecks = 0
                delayUntil(wait.maxTicks.toLong() * TICK_MILLIS, TICK_MILLIS) {
                    idleChecks = if (localPlayer.isMoving || localPlayer.isAnimating) 0 else idleChecks + 1
                    idleChecks >= wait.idleChecks
                }
            }
            is Wait.Sequence -> for (step in wait.steps) {
                if (stopped) return true
                beforeEachStep()
                if (!perform(step.run())) return false
            }
            is Wait.Loop -> while (!stopped) {
                beforeEachStep()
                val next = wait.step.run() ?: break
                if (!perform(next)) return false
            }
        }
        return true
    }
}

/** One step of a [Wait.sequence]: act, then return what to wait for before the next step, or null for nothing. */
fun interface Step {
    fun run(): Wait?
}

/**
 * What a [JavaScript] asks the engine to wait for. Build one with the static factories —
 * prefer [until] over [ms] wherever an outcome can be observed, so the script reacts to the
 * game rather than to a guess.
 */
sealed class Wait {

    class Millis internal constructor(val mean: Int, val variance: Int) : Wait()

    class Until internal constructor(
        val predicate: BooleanSupplier,
        val timeoutMillis: Long,
        val pollMillis: Int,
    ) : Wait()

    class While internal constructor(
        val predicate: BooleanSupplier,
        val timeoutMillis: Long,
    ) : Wait()

    class XpDrop internal constructor(val skill: Skill?, val timeoutMillis: Long) : Wait()

    class Idle internal constructor(val maxTicks: Int, val idleChecks: Int) : Wait()

    class Sequence internal constructor(val steps: List<Step>) : Wait()

    class Loop internal constructor(val step: Step) : Wait()

    object Abort : Wait()

    companion object {
        /** A fixed pause. Prefer the randomised overload; a constant delay is a recognisable pattern. */
        @JvmStatic
        fun ms(millis: Int): Wait = Millis(millis, 0)

        /** A randomised pause around [mean], spread by [variance]. */
        @JvmStatic
        fun ms(mean: Int, variance: Int): Wait = Millis(mean, variance)

        /** A pause picked uniformly between [minMillis] and [maxMillis], inclusive. */
        @JvmStatic
        fun between(minMillis: Int, maxMillis: Int): Wait =
            Millis(ThreadLocalRandom.current().nextInt(minMillis, maxMillis + 1), 0)

        /** [ticks] game ticks of 600 ms (fractions allowed), plus 0..[jitterMillis] ms picked uniformly. */
        @JvmStatic
        @JvmOverloads
        fun ticks(ticks: Double, jitterMillis: Int = 0): Wait = ticks(ticks, 0, jitterMillis)

        /** [ticks] game ticks of 600 ms (fractions allowed), plus [minJitterMillis]..[maxJitterMillis] ms picked uniformly. */
        @JvmStatic
        fun ticks(ticks: Double, minJitterMillis: Int, maxJitterMillis: Int): Wait =
            Millis(
                (ticks * TICK_MILLIS).roundToInt() +
                    ThreadLocalRandom.current().nextInt(minJitterMillis, maxJitterMillis + 1),
                0,
            )

        /** Wait until [predicate] holds, giving up after [timeoutMillis]. */
        @JvmStatic
        @JvmOverloads
        fun until(predicate: BooleanSupplier, timeoutMillis: Long, pollMillis: Int = 100): Wait =
            Until(predicate, timeoutMillis, pollMillis)

        /** Wait while [predicate] holds, giving up after [timeoutMillis]. */
        @JvmStatic
        fun whileTrue(predicate: BooleanSupplier, timeoutMillis: Long): Wait =
            While(predicate, timeoutMillis)

        /** Wait for the next experience drop, optionally in one [skill]. */
        @JvmStatic
        @JvmOverloads
        fun xpDrop(skill: Skill? = null, timeoutMillis: Long = 15000): Wait =
            XpDrop(skill, timeoutMillis)

        /**
         * Wait for the player to stop moving and animating: checked once a tick, finished once
         * [idleChecks] checks in a row see nothing going on, or after [maxTicks] ticks.
         */
        @JvmStatic
        fun untilIdle(maxTicks: Int, idleChecks: Int): Wait = Idle(maxTicks, idleChecks)

        /**
         * Run [steps] one after another, each performing its own wait before the next starts — the
         * non-blocking way to write "click, wait, click, wait". A step runs only when the one before
         * it has finished waiting, so it always sees the game as it is at that moment.
         */
        @JvmStatic
        fun sequence(vararg steps: Step): Wait = Sequence(steps.toList())

        /**
         * Run [step] again and again, performing the wait it returns each time, until it returns null.
         * The non-blocking form of a `while` loop with a sleep inside it.
         */
        @JvmStatic
        fun loop(step: Step): Wait = Loop(step)

        /**
         * Returned from a step: skip every remaining step of the sequences and loops it belongs to, and
         * go straight on to the next [JavaScript.onLoop].
         */
        @JvmStatic
        fun abort(): Wait = Abort
    }
}
