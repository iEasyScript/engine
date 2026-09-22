package com.projectx.game.input

import com.projectx.game.bootstrap.Bootstrap

/**
 * One source of input per client cycle, priority natural > action > wire. Two sends in a window that
 * naturally holds one is the easiest possible bot signature.
 *
 * Natural input is detected from the click ring, which the game has not yet drained when the wire path
 * runs. An action cannot be detected that way: the right- and middle-button dispatch runs listener slots
 * rather than enqueuing a ring entry, so its effect on the ring is not guaranteed. The action path
 * therefore reports itself here, and an unknown cycle denies the wire path rather than guessing.
 */
object InputArbiter {
    private const val NO_CYCLE = Int.MIN_VALUE

    @Volatile private var actionCycle: Int = NO_CYCLE

    @Volatile private var lastActionMillis: Long = 0L

    /** How long since the engine last gave the client something to treat as input. */
    val millisSinceActionInput: Long
        get() = lastActionMillis.let { if (it == 0L) Long.MAX_VALUE else System.currentTimeMillis() - it }

    @Volatile var wireSendsDeniedByAction: Long = 0L
        private set

    fun recordActionInput() {
        actionCycle = currentCycleOrNull() ?: NO_CYCLE
        lastActionMillis = System.currentTimeMillis()
    }

    fun mayWireSend(): Boolean {
        val cycle = currentCycleOrNull() ?: return false
        if (actionCycle != cycle) return true
        wireSendsDeniedByAction++
        return false
    }

    private fun currentCycleOrNull(): Int? = runCatching { Bootstrap.client.clientCycle }.getOrNull()
}
