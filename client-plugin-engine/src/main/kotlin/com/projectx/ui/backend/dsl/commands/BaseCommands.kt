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
    enum class Scope {
        OPEN,
        /** Closed, and ImGui wants no end call: a table, tab bar, menu, popup or tree node that did not open. */
        CLOSED,
        /** Closed, but ImGui still requires the end call: a collapsed or clipped window or child window. */
        CLOSED_NEEDS_END,
        /** Never begun natively, because an enclosing scope was already closed. */
        SKIPPED,
    }

    private var skipDepth: Int = 0
    private val openStack = ArrayDeque<Scope>()

    fun isSkipping(): Boolean = skipDepth > 0

    fun begin(open: Boolean) = push(if (open) Scope.OPEN else Scope.CLOSED)

    /**
     * For Begin()/BeginChild(), which must always be paired with End()/EndChild() whatever they
     * returned. Their contents are still skipped when they report closed.
     */
    fun beginAlwaysEnded(open: Boolean) = push(if (open) Scope.OPEN else Scope.CLOSED_NEEDS_END)

    fun beginSkipped() = push(Scope.SKIPPED)

    private fun push(scope: Scope) {
        openStack.addLast(scope)
        if (scope != Scope.OPEN) skipDepth++
    }

    inline fun end(onOpenEnd: () -> Unit) {
        if (openStack.isEmpty()) return
        when (popScope()) {
            Scope.OPEN -> onOpenEnd()
            Scope.CLOSED_NEEDS_END -> {
                closeSkipped()
                onOpenEnd()
            }
            Scope.CLOSED, Scope.SKIPPED -> closeSkipped()
        }
    }

    fun popScope(): Scope = openStack.removeLast()

    fun closeSkipped() {
        skipDepth--
    }
}