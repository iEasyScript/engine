package com.projectx.ui.backend.rendering

import com.projectx.ui.backend.dsl.commands.ImGuiCommand
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance command buffer implementation using ring buffer pattern.
 * Reduces allocations and improves cache locality compared to linked structures.
 */
class CommandBuffer(private val capacity: Int = 8192) {
    private val commands = arrayOfNulls<ImGuiCommand>(capacity)
    private val head = AtomicInteger(0)
    private val tail = AtomicInteger(0)

    fun offer(command: ImGuiCommand): Boolean {
        val currentTail = tail.get()
        val nextTail = (currentTail + 1) % capacity

        if (nextTail == head.get()) {
            return false
        }

        commands[currentTail] = command
        tail.set(nextTail)
        return true
    }

    fun offerBatch(batch: List<ImGuiCommand>): Boolean {
        val batchSize = batch.size
        val currentTail = tail.get()
        val currentHead = head.get()

        val available = if (currentHead <= currentTail) {
            capacity - currentTail + currentHead - 1
        } else {
            currentHead - currentTail - 1
        }

        if (batchSize > available) {
            return false
        }

        var writePos = currentTail
        for (command in batch) {
            commands[writePos] = command
            writePos = (writePos + 1) % capacity
        }

        tail.set(writePos)
        return true
    }

    fun drain(processor: (ImGuiCommand) -> Unit) {
        val currentHead = head.get()
        val currentTail = tail.get()

        if (currentHead == currentTail) {
            return
        }

        var readPos = currentHead
        while (readPos != currentTail) {
            val command = commands[readPos]
            if (command != null) {
                processor(command)
                commands[readPos] = null
            }
            readPos = (readPos + 1) % capacity
        }

        head.set(currentTail)
    }

    fun clear() {
        val currentHead = head.get()
        val currentTail = tail.get()

        var pos = currentHead
        while (pos != currentTail) {
            commands[pos] = null
            pos = (pos + 1) % capacity
        }

        head.set(0)
        tail.set(0)
    }

    fun isEmpty(): Boolean = head.get() == tail.get()

    fun size(): Int {
        val h = head.get()
        val t = tail.get()
        return if (t >= h) {
            t - h
        } else {
            capacity - h + t
        }
    }
}