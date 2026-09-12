package com.projectx.ui.backend.dsl.commands

import com.projectx.ui.backend.dsl.ImGuiState
import java.util.concurrent.atomic.AtomicReference

sealed class ImGuiCommand {
    abstract fun execute()
    open fun isStructural(): Boolean = false

    fun safeExecute(): Boolean {
        return try {
            if (ImGuiExecState.isSkipping() && !isStructural()) {
                true
            } else {
                execute()
                true
            }
        } catch (e: Throwable) {
            System.err.println("ImGui command failed: ${this.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
            false
        }
    }
}

/**
 * Runs an arbitrary Kotlin callback during command execution. Being non-structural, it is
 * skipped while inside a closed context (e.g. an unopened popup), so a callback placed in a
 * popup/menu block only fires on the frames the popup is actually open.
 */
data class CallbackCommand(val run: () -> Unit) : ImGuiCommand() {
    override fun execute() {
        try { run() } catch (e: Throwable) {
            System.err.println("CallbackCommand failed: ${e.message}")
            e.printStackTrace()
        }
    }
}

data class SetBoolStateFromRefCommand(
    val state: ImGuiState<Boolean>,
    val ref: AtomicReference<Boolean>
) : ImGuiCommand() {
    override fun execute() {
        state.value = ref.get()
    }
}

internal object ImGuiExecState {
    private var skipDepth: Int = 0
    private val openStack = ArrayDeque<Boolean>()

    fun isSkipping(): Boolean = skipDepth > 0

    fun begin(open: Boolean) {
        openStack.addLast(open)
        if (!open) skipDepth++
    }

    fun beginSkipped() {
        openStack.addLast(false)
        skipDepth++
    }

    inline fun end(onOpenEnd: () -> Unit) {
        if (openStack.isEmpty()) return
        val open = openStack.removeLast()
        if (open) {
            onOpenEnd()
        } else {
            skipDepth--
        }
    }
}