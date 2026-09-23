package com.projectx.ui

import androidx.compose.runtime.mutableStateOf
import com.projectx.ui.backend.dsl.ImGuiState

/**
 * A setting the overlay shows and the engine reads.
 *
 * Held in Compose snapshot state, so a change made anywhere - a hook, a script, the panel itself - redraws what
 * shows it. It is still an [ImGuiState], which keeps every reader of `.value` unchanged; it just has no native
 * buffer, so it cannot be handed to an ImGui widget.
 */
class Setting<T>(initial: T) : ImGuiState<T>() {
    private val state = mutableStateOf(initial)

    override var value: T
        get() = state.value
        set(value) {
            state.value = value
        }
}

fun <T> setting(initial: T): Setting<T> = Setting(initial)
