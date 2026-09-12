package com.projectx.game.bootstrap

/**
 * Identity of the client's main-logic thread, stamped by the main-logic hook each frame.
 *
 * Anything that writes into a client structure the game reads without locks — the mouse click ring above
 * all — has to prove it is on this thread. Off-thread writes there corrupt state the game is mid-way
 * through reading, and the resulting crash lands nowhere near the cause.
 */
object GameThread {
    private const val UNKNOWN = -1L

    @Volatile private var threadId: Long = UNKNOWN

    fun mark() {
        threadId = Thread.currentThread().threadId()
    }

    fun isCurrent(): Boolean {
        val known = threadId
        return known != UNKNOWN && Thread.currentThread().threadId() == known
    }
}
