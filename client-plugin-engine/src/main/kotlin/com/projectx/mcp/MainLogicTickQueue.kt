package com.projectx.mcp

import java.util.concurrent.ArrayBlockingQueue

object MainLogicTickQueue {
    const val CAPACITY = 64

    private val queue = ArrayBlockingQueue<Runnable>(CAPACITY)
    private val onTick = ThreadLocal.withInitial { false }

    fun isOnGameThread(): Boolean = onTick.get()

    fun enqueue(task: Runnable): Boolean = queue.offer(task)

    fun drain() {
        if (onTick.get()) return
        onTick.set(true)
        try {
            var processed = 0
            while (processed < CAPACITY) {
                val task = queue.poll() ?: break
                try {
                    task.run()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
                processed++
            }
        } finally {
            onTick.set(false)
        }
    }

    fun pendingCount(): Int = queue.size
}
