package com.projectx.script

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * Drives script coroutines from the client's main-logic tick.
 *
 * Timed waits must be scheduled here, not on kotlinx's default executor. `withTimeoutOrNull` routes its
 * deadline through [invokeOnTimeout], whose default implementation uses a thread the engine neither owns
 * nor keeps alive - leaving a wait that could resume script code off the game thread, or never fire at
 * all once that thread had exited, orphaning the coroutine with nothing left to resume it.
 */
@OptIn(InternalCoroutinesApi::class)
class ClientPulseDispatcher : CoroutineDispatcher(), Delay {
    private val tasks = mutableListOf<ScheduledTask>()
    private val timeouts = mutableListOf<ScheduledTimeout>()
    private val pending = ArrayDeque<Runnable>()

    private var lastWorkAt = 0L
    private var lastReportAt = 0L

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(this) { pending.addLast(block) }
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        synchronized(this) {
            tasks.add(ScheduledTask(System.currentTimeMillis() + timeMillis, continuation))
        }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val timeout = ScheduledTimeout(System.currentTimeMillis() + timeMillis, block)
        synchronized(this) { timeouts.add(timeout) }
        return DisposableHandle { synchronized(this) { timeouts.remove(timeout) } }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val readyContinuations = mutableListOf<CancellableContinuation<Unit>>()
        val readyTimeouts = mutableListOf<Runnable>()
        val readyBlocks = mutableListOf<Runnable>()
        val skipped = mutableListOf<CancellableContinuation<Unit>>()

        synchronized(this) {
            tasks.removeAll { task ->
                (task.scheduledTime <= now).also { if (it) readyContinuations.add(task.continuation) }
            }
            timeouts.removeAll { timeout ->
                (timeout.scheduledTime <= now).also { if (it) readyTimeouts.add(timeout.block) }
            }
            readyBlocks.addAll(pending)
            pending.clear()
        }

        // Anything removed here is no longer tracked, so a resume that fails silently strands its coroutine
        // for good - report rather than swallow, and say which route it came from.
        readyContinuations.forEach { continuation ->
            if (!continuation.isActive) {
                skipped += continuation
            } else {
                try {
                    continuation.resume(Unit)
                } catch (t: Throwable) {
                    println("[PULSE] resume(task) threw ${t::class.java.name}: ${t.message}")
                }
            }
        }
        readyTimeouts.forEach {
            try {
                it.run()
            } catch (t: Throwable) {
                println("[PULSE] timeout block threw ${t::class.java.name}: ${t.message}")
            }
        }
        readyBlocks.forEach {
            try {
                it.run()
            } catch (t: Throwable) {
                println("[PULSE] dispatched block threw ${t::class.java.name}: ${t.message}")
            }
        }
        if (skipped.isNotEmpty()) println("[PULSE] dropped ${skipped.size} inactive continuation(s)")

        val didWork = readyContinuations.isNotEmpty() || readyTimeouts.isNotEmpty() || readyBlocks.isNotEmpty()
        if (didWork) lastWorkAt = now else reportStarvation(now)
    }

    /**
     * The tick runs ~50x a second, so a long stretch with nothing resumed means the coroutine is parked with
     * no pending wakeup. Print what each queue still holds: empty queues mean the continuation was dropped,
     * a populated one means it is being skipped rather than lost.
     */
    private fun reportStarvation(now: Long) {
        if (lastWorkAt == 0L) lastWorkAt = now
        if (now - lastWorkAt < STARVATION_MS || now - lastReportAt < STARVATION_MS) return
        lastReportAt = now
        synchronized(this) {
            val nextDue = tasks.minOfOrNull { it.scheduledTime }?.let { it - now }
            println(
                "[PULSE] no work for ${(now - lastWorkAt) / 1000}s - " +
                    "tasks=${tasks.size} timeouts=${timeouts.size} pending=${pending.size} nextDueMs=$nextDue"
            )
        }
    }

    private companion object {
        const val STARVATION_MS = 10_000L
    }

    private class ScheduledTask(
        val scheduledTime: Long,
        val continuation: CancellableContinuation<Unit>
    )

    private class ScheduledTimeout(
        val scheduledTime: Long,
        val block: Runnable
    )
}
