package com.projectx.ui.backend.rendering

import com.projectx.ui.backend.dsl.commands.ImGuiCommand
import java.util.concurrent.atomic.AtomicReference

/**
 * Triple-buffered storage for precomputed ImGuiCommand frames.
 * - Sticky front: if no new ready buffer exists, returns the previous front again.
 * - SPSC: single producer (worker) and single consumer (render thread).
 */
class FrameCommandBuffers {
    private val bufA: MutableList<ImGuiCommand> = ArrayList(1024)
    private val bufB: MutableList<ImGuiCommand> = ArrayList(1024)
    private val bufC: MutableList<ImGuiCommand> = ArrayList(1024)

    private val readyRef = AtomicReference<List<ImGuiCommand>?>(null)
    @Volatile private var frontRef: List<ImGuiCommand>? = null
    private val inUseRef = AtomicReference<List<ImGuiCommand>?>(null)

    @Volatile var framesPromoted: Long = 0
        private set
    @Volatile var framesReused: Long = 0
        private set

    /** Acquire a list that is free to write (not the current front or ready). */
    fun acquireForWrite(): MutableList<ImGuiCommand> {
        val front = frontRef
        val ready = readyRef.get()
        val inUse = inUseRef.get()

        return when {
            bufA !== front && bufA !== ready && bufA !== inUse -> bufA
            bufB !== front && bufB !== ready && bufB !== inUse -> bufB
            bufC !== front && bufC !== ready && bufC !== inUse -> bufC
            else -> {
                // Fallback: avoid the current in-use buffer at all costs
                if (bufA !== inUse) bufA else if (bufB !== inUse) bufB else bufC
            }
        }.also(MutableList<*>::clear)
    }

    /**
     * Publish a newly built list as ready for the next promotion.
     */
    fun publishReady(built: MutableList<ImGuiCommand>) {
        readyRef.set(built)
    }

    /**
     * On the render thread: promote ready to front if present; otherwise keep current front.
     */
    fun promoteIfReadyOrKeepFront(): List<ImGuiCommand> {
        val ready = readyRef.getAndSet(null)
        return if (ready != null) {
            frontRef = ready
            framesPromoted++
            ready
        } else {
            val current = frontRef
            if (current != null) {
                framesReused++
                current
            } else {
                emptyList()
            }
        }
    }

    /** Mark a list as being consumed by the render thread to prevent reuse/clearing. */
    fun beginConsume(list: List<ImGuiCommand>) {
        inUseRef.set(list)
    }

    /** Clear the in-use mark once rendering finishes for the list. */
    fun endConsume(list: List<ImGuiCommand>) {
        if (inUseRef.get() === list) {
            inUseRef.set(null)
        }
    }
}
