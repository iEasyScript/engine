package com.projectx.store

import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntitlementsTest {
    private val now: Instant = Instant.parse("2026-01-01T12:00:00Z")
    private val nowNanos = TimeUnit.HOURS.toNanos(1000)
    private val second = TimeUnit.SECONDS.toNanos(1)

    private fun entitled(
        expiry: Instant? = null,
        trialEndNanos: Long? = null,
        lastAnsweredNanos: Long = nowNanos,
    ) = Entitlements.entitled(expiry, trialEndNanos, now, nowNanos, lastAnsweredNanos)

    @Test
    fun `access held until its expiry`() {
        assertTrue(entitled(expiry = now.plusSeconds(60)))
    }

    @Test
    fun `access ends at its expiry`() {
        assertFalse(entitled(expiry = now.minusSeconds(1)))
    }

    @Test
    fun `nothing owned means nothing runs`() {
        assertFalse(entitled())
    }

    @Test
    fun `paid access rides out an outage, because downtime is not a reason to take it away`() {
        // The store has not answered for an hour; the expiry is still in the future.
        assertTrue(entitled(expiry = now.plusSeconds(3600), lastAnsweredNanos = nowNanos - TimeUnit.HOURS.toNanos(1)))
    }

    @Test
    fun `a trial runs while the store keeps confirming it`() {
        assertTrue(entitled(trialEndNanos = nowNanos + 60 * second, lastAnsweredNanos = nowNanos - second))
    }

    @Test
    fun `a trial ends once the store stops answering, so blocking us cannot extend it`() {
        assertFalse(
            entitled(
                trialEndNanos = nowNanos + 3600 * second,
                lastAnsweredNanos = nowNanos - 200 * second,
            ),
        )
    }

    @Test
    fun `a trial ends on time`() {
        assertFalse(entitled(trialEndNanos = nowNanos - second))
    }

    @Test
    fun `a trial is judged on the monotonic clock, so moving the machine clock back does not extend it`() {
        // `now` is dragged a year into the past; only the nano clock decides a trial.
        val lastYear = now.minusSeconds(365L * 24 * 3600)
        assertFalse(
            Entitlements.entitled(
                expiry = null,
                trialEndNanos = nowNanos - second,
                now = lastYear,
                nowNanos = nowNanos,
                lastAnsweredNanos = nowNanos,
            ),
        )
    }

    @Test
    fun `a trial outranks a subscription, being the stricter of the two`() {
        // Subscription good for an hour, trial already over and the store silent.
        assertFalse(
            entitled(
                expiry = now.plusSeconds(3600),
                trialEndNanos = nowNanos - second,
                lastAnsweredNanos = nowNanos,
            ),
        )
    }

    @Test
    fun `a script the store does not sell is never gated`() {
        assertFalse(Entitlements.isPaid("SomeCommunityScript"))
    }

    @Test
    fun `the remembered paid list survives a round trip`() {
        val names = setOf("RasialPlugin", "ExaltedQuarry")
        assertEquals(names, Entitlements.decodePaid(Entitlements.encodePaid(names)))
    }

    @Test
    fun `nothing paid is remembered as nothing, not as one nameless script`() {
        assertEquals(emptySet(), Entitlements.decodePaid(Entitlements.encodePaid(emptySet())))
    }
}
