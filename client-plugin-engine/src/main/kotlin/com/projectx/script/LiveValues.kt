package com.projectx.script

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.util.Collections
import java.util.WeakHashMap

/**
 * Game state read for a script's panel on the game thread, published as Compose state for the render thread.
 * Refreshed while the script is running, each at its own interval.
 */
internal object LiveValues {
    private class Live<T>(val everyMillis: Long, val read: () -> T, val state: MutableState<T>) {
        var lastRead = 0L

        fun refresh(now: Long) {
            if (now - lastRead < everyMillis) return
            lastRead = now
            val next = try {
                read()
            } catch (t: Throwable) {
                println("[Script] live value failed: ${t::class.simpleName}: ${t.message}")
                return
            }
            if (next != state.value) state.value = next
        }
    }

    private val values = Collections.synchronizedMap(WeakHashMap<Script, MutableList<Live<*>>>())

    fun <T> register(script: Script, initial: T, everyMillis: Long, read: () -> T): MutableState<T> {
        val state = mutableStateOf(initial)
        values.getOrPut(script) { mutableListOf() } += Live(everyMillis.coerceAtLeast(0), read, state)
        return state
    }

    /** Game thread only. */
    fun refresh(script: Script) {
        val live = synchronized(values) { values[script]?.toList() } ?: return
        val now = System.currentTimeMillis()
        live.forEach { it.refresh(now) }
    }
}
