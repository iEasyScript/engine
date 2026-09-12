package com.projectx.script.impl.devin.zuk

import java.util.concurrent.ConcurrentHashMap

/**
 * Every automated input goes through here, capped at **one input per game tick**.
 *
 * The underlying api calls are unguarded — `throwVulnBomb()` re-clicks whenever vulnerability is not
 * yet on the target, which is also true while the bomb is on cooldown or mid-flight — so a loop that
 * calls them directly issues one click per iteration. That shipped once and produced 257 interface
 * clicks against 206 real ones, peaking at 11 in a second.
 *
 * A failed attempt must back off on its own; it can never rely on the action succeeding.
 */
object ZukInputGate {

    /** The server acts once per tick, so nothing can be gained by sending faster than this. */
    const val TICK_MS = 600L

    /** Comfortably longer than the bomb's own 1.8s cooldown, so a refusal is never re-sent immediately. */
    const val VULN_BOMB_MS = 2_400L

    /** One global-cooldown cycle plus margin, so a shield-break press cannot double-fire. */
    const val SHIELD_BREAK_MS = 2_400L

    const val PRAYER_MS = TICK_MS

    private val lastAttempt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastAnyAttempt = 0L

    /**
     * Two gates, and the global one is the important half: a per-action cooldown alone still lets
     * several different actions fire in the same tick, and any new caller that forgets to pass a
     * sensible cooldown reintroduces the flood. Nothing here can exceed one input per tick, ever.
     */
    @Synchronized
    fun allow(key: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAnyAttempt < TICK_MS) return false
        val previous = lastAttempt[key]
        if (previous != null && now - previous < maxOf(cooldownMs, TICK_MS)) return false
        lastAttempt[key] = now
        lastAnyAttempt = now
        return true
    }

    @Synchronized
    fun reset() {
        lastAttempt.clear()
        lastAnyAttempt = 0
    }
}
