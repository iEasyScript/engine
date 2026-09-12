package com.projectx.game.input

/**
 * Per-tick snapshot of game state that conditions the input model.
 *
 * ⛔ No camera state, deliberately. The camera is never read, never recorded, never modelled and never sent —
 * see `.claude/memory/engine-synthetic-input.md`. This class previously declared yaw and pitch fields that
 * every construction site hardcoded to zero and nothing ever consumed; they are gone so their presence cannot
 * be mistaken for an intention to add camera support.
 */
data class GameContext(
    val gameTick: Int,
    val mainState: Int,
    val playerX: Int,
    val playerY: Int,
    val playerPlane: Int,
    val timestampNanos: Long
)
