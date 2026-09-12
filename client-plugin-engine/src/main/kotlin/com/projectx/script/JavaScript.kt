package com.projectx.script

import org.projectx.core.game.skill.Skill
import java.util.function.BooleanSupplier

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

    final override suspend fun loop() {
        when (val wait = onLoop()) {
            is Wait.Millis -> delay(wait.mean, wait.variance)
            is Wait.Until -> delayUntil(wait.timeoutMillis, wait.pollMillis) { wait.predicate.asBoolean }
            is Wait.While -> delayWhile(wait.timeoutMillis) { wait.predicate.asBoolean }
            is Wait.XpDrop -> waitForXPDrop(wait.skill, wait.timeoutMillis)
        }
    }
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

    companion object {
        /** A fixed pause. Prefer the randomised overload; a constant delay is a recognisable pattern. */
        @JvmStatic
        fun ms(millis: Int): Wait = Millis(millis, 0)

        /** A randomised pause around [mean], spread by [variance]. */
        @JvmStatic
        fun ms(mean: Int, variance: Int): Wait = Millis(mean, variance)

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
    }
}
