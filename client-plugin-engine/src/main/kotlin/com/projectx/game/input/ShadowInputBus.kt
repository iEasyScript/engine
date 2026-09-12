package com.projectx.game.input

import java.util.concurrent.LinkedBlockingDeque

object ShadowInputBus {
    private const val CAPACITY = 32
    private val queue = LinkedBlockingDeque<ShadowIntent>(CAPACITY)

    fun publish(intent: ShadowIntent) {
        while (!queue.offer(intent)) {
            queue.pollFirst() ?: break
        }
    }

    fun poll(): ShadowIntent? = queue.pollFirst()

    fun size(): Int = queue.size

    fun isEmpty(): Boolean = queue.isEmpty()

    fun clear() = queue.clear()
}
