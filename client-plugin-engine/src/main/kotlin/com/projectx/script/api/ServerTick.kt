package com.projectx.script.api

import com.projectx.script.Script

/**
 * The server's game tick, counted from the end-of-tick packet it sends once per 600 ms cycle. Combat, ability
 * cooldowns and prayers resolve on this clock, and a local 600 ms timer drifts away from it within a few ticks, so
 * anything that must land on a tick boundary (a rotation, a prayer flick) counts these instead.
 *
 * [count] only moves while the game connection is delivering packets: it stands still at the lobby and during a
 * disconnect.
 */
object ServerTick {
    @Volatile
    private var ticks = 0L

    @Volatile
    private var lastTickAt = 0L

    /** Server ticks seen since the engine loaded. */
    @JvmStatic
    val count: Long get() = ticks

    /** Milliseconds since the last server tick ended, or [Long.MAX_VALUE] before the first one. */
    @JvmStatic
    val millisSinceTick: Long get() = lastTickAt.let { if (it == 0L) Long.MAX_VALUE else System.currentTimeMillis() - it }

    internal fun onTickEnd() {
        lastTickAt = System.currentTimeMillis()
        ticks++
    }
}

/** Waits for the next server tick to end. Returns false if none arrived within [timeoutMillis]. */
suspend fun Script.awaitServerTick(timeoutMillis: Long = 1800): Boolean = awaitServerTicks(1, timeoutMillis)

/** Waits for [ticks] more server ticks to end. Returns false if they did not all arrive within [timeoutMillis]. */
suspend fun Script.awaitServerTicks(ticks: Int, timeoutMillis: Long = ticks * 600L + 1200L): Boolean {
    val target = ServerTick.count + ticks
    delayUntil(timeoutMillis, pollingDelayMillis = 10) { ServerTick.count >= target }
    return ServerTick.count >= target
}
