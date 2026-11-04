package com.projectx.traversal.nodes

import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.util.nonProfiledGaussian
import world.gregs.voidps.type.Tile

/** The outcome waits the traversal nodes share: each click waits for what it set off, never a fixed sleep. */
internal object NodeWaits {
    const val WALK_START_TIMEOUT_MS = 1_800L
    const val WALK_LEG_TIMEOUT_MS = 8_000L
    const val CLICK_PACING_MS = 600L
    const val INTERACTION_START_MS = 2_000L
    const val INTERACTION_SETTLE_MS = 1_800L
    const val INTERACTION_MAX_MS = 20_000L
    const val ITEM_EFFECT_MAX_MS = 15_000L
    const val ANIMATION_GAP_MS = 1_200L

    /** Same plane and within [distance] tiles. */
    fun within(a: Tile, b: Tile, distance: Int): Boolean = a.plane == b.plane && a.getDistance(b) <= distance

    /** A gap around [meanMs] with a standard deviation of a quarter of it, never under a quarter of it. */
    fun clickGap(meanMs: Long = CLICK_PACING_MS): Long {
        val sd = (meanMs / 4).toInt()
        var gap: Long
        do {
            gap = nonProfiledGaussian(meanMs.toInt(), sd * sd).toLong()
        } while (gap < meanMs / 4)
        return gap
    }

    /** Waits for a walk to start, then to stop; false when it never started. Ends early on [abort]. */
    suspend fun Script.awaitWalk(startMs: Long, stopMs: Long, abort: () -> Boolean): Boolean {
        delayUntil(startMs) { abort() || localPlayer.isMoving }
        if (abort()) return true
        if (!localPlayer.isMoving) return false
        delayUntil(stopMs) { abort() || !localPlayer.isMoving }
        return true
    }

    /**
     * Waits for a clicked interaction to start (a walk or an animation) and then to settle: [done], or [settleMs] with
     * neither, at most [maxMs]. False when it never started.
     */
    suspend fun Script.awaitInteraction(settleMs: Long = INTERACTION_SETTLE_MS, maxMs: Long = INTERACTION_MAX_MS, done: () -> Boolean): Boolean {
        delayUntil(INTERACTION_START_MS) { done() || busy() }
        if (done()) return true
        if (!busy()) return false
        var idleSince = System.currentTimeMillis()
        delayUntil(maxMs) {
            val now = System.currentTimeMillis()
            if (busy()) idleSince = now
            done() || now - idleSince >= settleMs
        }
        return true
    }

    /**
     * After an item click that may or may not teleport: until the player leaves [from], or an item that only animates
     * has stopped animating for [ANIMATION_GAP_MS]; a teleport's cast and arrival animations sit either side of the move.
     */
    suspend fun Script.awaitItemEffect(from: Tile, done: () -> Boolean) {
        val arrived = { done() || localPlayer.tile != from }
        delayUntil(WALK_START_TIMEOUT_MS) { arrived() || localPlayer.isAnimating }
        if (arrived() || !localPlayer.isAnimating) return
        var stillSince = 0L
        delayUntil(ITEM_EFFECT_MAX_MS) {
            val now = System.currentTimeMillis()
            when {
                arrived() -> true
                localPlayer.isAnimating -> {
                    stillSince = 0L
                    false
                }
                stillSince == 0L -> {
                    stillSince = now
                    false
                }
                else -> now - stillSince >= ANIMATION_GAP_MS
            }
        }
    }

    private fun busy(): Boolean = localPlayer.isMoving || localPlayer.isAnimating
}
