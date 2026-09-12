package com.projectx.game.input

sealed interface InputEvent {
    val timestampNanos: Long
    val gameTick: Int
}

data class MouseMotionEvent(
    override val timestampNanos: Long,
    override val gameTick: Int,
    val x: Int,
    val y: Int
) : InputEvent

data class MouseButtonEvent(
    override val timestampNanos: Long,
    override val gameTick: Int,
    val x: Int,
    val y: Int,
    val button: MouseButton,
    val pressed: Boolean
) : InputEvent

data class MouseScrollEvent(
    override val timestampNanos: Long,
    override val gameTick: Int,
    val x: Int,
    val y: Int,
    val scrollDelta: Int
) : InputEvent

data class KeyboardEvent(
    override val timestampNanos: Long,
    override val gameTick: Int,
    val keyCode: Int,
    val pressed: Boolean
) : InputEvent

/**
 * A typed character, which the client delivers on its own path rather than as part of a key press — the same
 * physical keystroke produces both, and only this one knows what was actually typed after the keymap and any
 * modifiers have been applied.
 */
data class KeyCharEvent(
    override val timestampNanos: Long,
    override val gameTick: Int,
    val charCode: Int
) : InputEvent

enum class MouseButton(val id: Int) {
    LEFT(1),
    MIDDLE(2),
    RIGHT(3);

    companion object {
        fun fromId(id: Int): MouseButton? = entries.find { it.id == id }
    }
}
