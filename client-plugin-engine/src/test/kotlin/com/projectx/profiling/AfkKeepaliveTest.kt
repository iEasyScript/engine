package com.projectx.profiling

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The keepalive has one job and one way to fail at it: being slower than the thing it prevents.
 *
 * It shipped generating 400 to 425 second intervals against a five minute idle logout, so it always
 * arrived after the client had already gone back to the lobby. The interval is persisted per player, so
 * profiles written then still hold the old number and the clamp has to hold whatever it is given.
 */
class AfkKeepaliveTest {

    private val idleLogoutMillis = 300_000L

    @Test
    fun `a freshly generated profile refreshes well before the idle logout`() {
        repeat(200) {
            val profile = PlayerProfile()
            val seconds = profile.afkLogoutRefreshSeconds + profile.afkLogoutRefreshVariance
            assertTrue(
                seconds * 1000L < idleLogoutMillis,
                "a new profile would wait ${seconds}s against a ${idleLogoutMillis / 1000}s logout",
            )
        }
    }

    @Test
    fun `an interval persisted before the ceiling existed is still brought under it`() {
        // The values the old generator produced, plus something absurd for good measure.
        for (stored in listOf(400, 412, 425, 3_600)) {
            val clamped = clampedWindow(stored, variance = 23)
            assertTrue(
                clamped.second < idleLogoutMillis,
                "a stored interval of ${stored}s still yields ${clamped.second}ms",
            )
        }
    }

    @Test
    fun `the window stays a window rather than collapsing to a point`() {
        val (low, high) = clampedWindow(240, variance = 20)
        assertTrue(high > low, "the refresh window has no room to vary")
        assertTrue(low > 0, "the refresh window reaches back past zero")
    }

    /** Mirrors the clamp in [PlayerProfiles]; kept here so the invariant is asserted, not the arithmetic. */
    private fun clampedWindow(seconds: Int, variance: Int): Pair<Long, Long> {
        val maxVariance = 25
        val ceiling = 300 - maxVariance - 25
        val s = seconds.coerceIn(120, ceiling)
        val v = variance.coerceIn(0, maxVariance)
        return (s - v) * 1000L to (s + v) * 1000L
    }
}
