package com.projectx.game.input

import java.util.concurrent.ConcurrentHashMap

/**
 * The right and middle buttons have no client entry point to hook, so the recorder samples their shared
 * state byte once per tick and emits an event on every change. A synthetic dispatch writes that same byte,
 * which the poller would otherwise record as a human click.
 *
 * Injection cannot be filtered by a flag held across the call, because the dispatch and the poll both run
 * on the game thread and never overlap. Instead the injecting side marks the button, and the next poll
 * adopts the observed value as its new baseline without emitting.
 */
internal object SyntheticButtonState {
    private val dirty = ConcurrentHashMap<Long, Boolean>()

    fun markWritten(stateOffset: Long) {
        dirty[stateOffset] = true
    }

    fun consumeWritten(stateOffset: Long): Boolean = dirty.remove(stateOffset) != null
}
