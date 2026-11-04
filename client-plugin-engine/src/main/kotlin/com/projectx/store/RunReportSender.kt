package com.projectx.store

import com.google.gson.JsonObject
import java.time.Duration

/** The HTTP status, or null when the site could not be reached. */
internal fun interface RunReportTransport {
    fun post(body: String, token: String): Int?
}

/** A report stays on disk until the site has it or refuses it; signed out, nothing is sent or kept. */
internal class RunReportSender(
    private val store: PendingRunReports,
    private val transport: RunReportTransport,
    private val token: () -> String?,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { SENT, DROP, KEEP, RETRY }

    fun report(body: JsonObject) {
        if (token() == null) return
        store.claim(store.add(body))?.let(::deliver)
    }

    fun sendPending() {
        store.claimAll().forEach(::deliver)
    }

    fun death(body: JsonObject) {
        val token = token() ?: return
        attempt(body.toString(), token)
    }

    private fun deliver(claimed: PendingRunReports.Claimed) {
        val token = token()
        if (token == null || tooOld(claimed.body)) {
            store.done(claimed)
            return
        }
        when (attempt(claimed.body.toString(), token)) {
            Outcome.SENT, Outcome.DROP -> store.done(claimed)
            Outcome.KEEP, Outcome.RETRY -> store.giveBack(claimed)
        }
    }

    private fun attempt(body: String, token: String): Outcome {
        var outcome = Outcome.RETRY
        for (wait in RETRY_MILLIS) {
            if (wait > 0) pause(wait)
            outcome = outcomeOf(runCatching { transport.post(body, token) }.getOrNull())
            if (outcome != Outcome.RETRY) break
        }
        return outcome
    }

    private fun tooOld(body: JsonObject): Boolean {
        val startedAt = runCatching { body.get("startedAt").asLong }.getOrNull() ?: return true
        return now() - startedAt > OLDEST_ACCEPTED.toMillis()
    }

    companion object {
        val RETRY_MILLIS = longArrayOf(0, 2_000, 5_000)

        /** The site turns away runs that started more than a week ago; an hour short of that leaves room for clocks. */
        val OLDEST_ACCEPTED: Duration = Duration.ofDays(7).minusHours(1)

        /** A refused sign-in waits for the next pass rather than being retried at once. */
        fun outcomeOf(status: Int?): Outcome = when {
            status == null -> Outcome.RETRY
            status in 200..299 -> Outcome.SENT
            status == 401 || status == 403 -> Outcome.KEEP
            status == 408 || status == 429 || status >= 500 -> Outcome.RETRY
            else -> Outcome.DROP
        }
    }
}
