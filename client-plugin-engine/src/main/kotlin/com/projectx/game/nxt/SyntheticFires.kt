package com.projectx.game.nxt

/** Counts every synthetic action fired, so a prediction built on one action can tell when another has replaced it. */
internal object SyntheticFires {
    @Volatile
    var count = 0L
        private set

    fun fired() {
        count++
    }
}
