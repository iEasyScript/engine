package com.projectx.ui.backend.rendering

import com.projectx.ui.backend.dsl.ImGuiState
import com.projectx.ui.backend.dsl.commands.ImGuiCommand
import com.projectx.ui.backend.dsl.commands.ImGuiExecState
import java.util.concurrent.ConcurrentHashMap

/**
 * Optimized command renderer using ring buffer and object pooling.
 * Reduces allocations and improves performance compared to the original implementation.
 */
object CommandRenderer {
    private val commandBuffer = CommandBuffer(capacity = 16384)
    private val sharedStates = ConcurrentHashMap<String, ImGuiState<*>>(64)

    private val captureSink: ThreadLocal<MutableList<ImGuiCommand>?> = ThreadLocal.withInitial { null }

    private val clickStates = BooleanArray(256)
    private val clickStateKeys = Array<String?>(256) { null }

    private val commandExecutor: (ImGuiCommand) -> Unit = { command ->
        executeCommand(command)
    }

    fun submitCommands(commands: List<ImGuiCommand>) {
        val sink = captureSink.get()
        if (sink != null) {
            sink.addAll(commands)
            return
        }
        if (!commandBuffer.offerBatch(commands)) {
            for (command in commands) {
                if (!commandBuffer.offer(command)) {
                    System.err.println("[OptimizedCommandRenderer] Command buffer full, dropping commands")
                    break
                }
            }
        }
    }

    /** Called from the EGLSwapBuffers hook. */
    fun executeQueuedCommands() {
        if (commandBuffer.isEmpty()) return
        commandBuffer.drain(commandExecutor)
    }

    fun executePrecomputed(commands: List<ImGuiCommand>) {
        if (commands.isEmpty()) return
        for (i in 0 until commands.size) {
            val cmd = commands[i]
            try {
                if (!ImGuiExecState.isSkipping() || cmd.isStructural()) {
                    cmd.execute()
                }
            } catch (e: Throwable) {
                System.err.println("ImGui command failed: ${cmd.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    private fun executeCommand(command: ImGuiCommand) {
        try {
            if (!ImGuiExecState.isSkipping() || command.isStructural()) {
                command.execute()
            }
        } catch (e: Throwable) {
            System.err.println("ImGui command failed: ${command.javaClass.simpleName} - ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getSharedState(key: String, factory: () -> ImGuiState<T>): ImGuiState<T> {
        return sharedStates.getOrPut(key, factory) as ImGuiState<T>
    }

    fun cleanup() {
        sharedStates.values.forEach { it.close() }
        sharedStates.clear()
        commandBuffer.clear()
        clickStates.fill(false)
        clickStateKeys.fill(null)
        CommandPool.clear()
    }

    fun <R> withCapture(target: MutableList<ImGuiCommand>, block: () -> R): R {
        captureSink.set(target)
        return try {
            block()
        } finally {
            captureSink.set(null)
        }
    }
}