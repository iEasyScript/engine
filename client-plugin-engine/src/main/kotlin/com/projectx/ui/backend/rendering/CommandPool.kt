package com.projectx.ui.backend.rendering

import com.projectx.ui.backend.dsl.commands.ImGuiCommand
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass

/**
 * Object pool for ImGui commands to reduce allocation pressure.
 * Commands are recycled after use to avoid constant object creation.
 */
object CommandPool {
    private val pools = mutableMapOf<KClass<*>, ConcurrentLinkedQueue<Any>>()
    private const val MAX_POOL_SIZE = 100

    @Suppress("UNCHECKED_CAST")
    fun <T : ImGuiCommand> borrow(klass: KClass<T>, factory: () -> T): T {
        val pool = pools.getOrPut(klass) { ConcurrentLinkedQueue() }
        return (pool.poll() as? T) ?: factory()
    }

    fun recycle(command: ImGuiCommand) {
        val klass = command::class
        val pool = pools.getOrPut(klass) { ConcurrentLinkedQueue() }

        if (pool.size < MAX_POOL_SIZE) {
            resetCommand(command)
            pool.offer(command)
        }
    }

    fun recycleBatch(commands: List<ImGuiCommand>) {
        for (command in commands) {
            recycle(command)
        }
    }

    private fun resetCommand(command: ImGuiCommand) {
    }

    fun clear() {
        pools.clear()
    }

    fun getStats(): Map<String, Int> {
        return pools.mapKeys { it.key.simpleName ?: "Unknown" }
            .mapValues { it.value.size }
    }
}

fun ImGuiCommand.executeAndRecycle() {
    try {
        this.execute()
    } finally {
        CommandPool.recycle(this)
    }
}