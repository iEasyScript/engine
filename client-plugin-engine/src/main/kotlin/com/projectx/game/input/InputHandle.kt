package com.projectx.game.input

import com.projectx.game.bootstrap.Bootstrap
import java.lang.foreign.MemorySegment

/**
 * The client's `Input` object, always derived from the Client struct rather than cached from whatever
 * `this` a hook happened to see — a stale pointer here writes into freed memory.
 */
internal object InputHandle {
    fun segmentOrNull(): MemorySegment? = runCatching { Bootstrap.client.input }.getOrNull()

    fun addressOrZero(): Long = runCatching { Bootstrap.client.input.address() }.getOrDefault(0L)
}
