package com.projectx.pathfinder.navpath

import com.projectx.util.nonProfiledGaussian
import com.projectx.webwalker.WebPath
import java.io.IOException

/**
 * Plans on the hosted navpathService and says when the local planner should be used instead. A busy server (503) is
 * asked again once or twice after a short pause; anything else that goes wrong - no token, a refused token, a timeout,
 * a dropped connection, an answer that does not parse or holds a step this walker cannot take - hands the walk to the
 * local planner at once. After [BREAKER_FAILURES] failures in a row the server is left alone for [BREAKER_MS], so an
 * outage never stalls walking. Runs on the planner thread only.
 */
internal class HostedPlanner(
    private val transport: NavTransport,
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = ::println,
) {
    sealed interface Outcome {
        data class Planned(val path: WebPath) : Outcome

        data class Fallback(val reason: String) : Outcome
    }

    private var failures = 0
    private var openUntil = 0L
    private var lastLogged: String? = null
    private var lastLoggedAt = 0L

    /** True while the breaker holds the server off. */
    @Synchronized
    fun resting(): Boolean = clock() < openUntil

    @Synchronized
    fun plan(request: NavRequest, acceptsTeleports: Boolean): Outcome {
        if (resting()) return Outcome.Fallback("the hosted planner is resting after failures")
        val bearer = token()?.takeIf { it.isNotBlank() } ?: return fallback("no store token", rest = BREAKER_MS)
        val body = request.toJson()
        var attempt = 0
        while (true) {
            val reply = try {
                transport.post(baseUrl().trimEnd('/') + ROUTE_PATH, bearer, body)
            } catch (e: IOException) {
                return failure("unreachable (${e.javaClass.simpleName})")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return failure("interrupted")
            } catch (e: RuntimeException) {
                return failure("request failed (${e.javaClass.simpleName})")
            }
            when {
                reply.status == 503 && attempt < BUSY_RETRIES -> {
                    attempt++
                    pause(nonProfiledGaussian(BUSY_BACKOFF_MS, BUSY_BACKOFF_VARIANCE).toLong().coerceIn(BUSY_BACKOFF_MIN_MS, BUSY_BACKOFF_MAX_MS))
                    continue
                }
                reply.status == 401 || reply.status == 403 -> return fallback("the store token was refused (HTTP ${reply.status})", rest = AUTH_REST_MS)
                reply.status == 400 -> return fallback("the planner refused the request: ${reply.body.take(120)}")
                reply.status !in 200..299 -> return failure("HTTP ${reply.status}")
            }
            val route = try {
                NavResultsParser.parse(reply.body)
            } catch (e: NavParseException) {
                return failure("an answer that does not parse: ${e.message}")
            }
            succeeded()
            return when (val mapped = NavRouteMapper.map(request.start, route)) {
                is NavRouteMapper.Mapped.Refused -> fallback(mapped.reason)
                is NavRouteMapper.Mapped.Route -> {
                    if (!acceptsTeleports && (1 until mapped.path.size).any { mapped.path.linkAt(it)?.isGlobal == true }) {
                        fallback("the route starts with a teleport this walk may not use")
                    } else {
                        Outcome.Planned(mapped.path)
                    }
                }
            }
        }
    }

    private fun succeeded() {
        failures = 0
    }

    private fun failure(reason: String): Outcome {
        if (++failures >= BREAKER_FAILURES) {
            failures = 0
            return fallback("$reason; resting for ${BREAKER_MS / 1000} s", rest = BREAKER_MS)
        }
        return fallback(reason)
    }

    private fun fallback(reason: String, rest: Long = 0): Outcome {
        if (rest > 0) openUntil = clock() + rest
        val now = clock()
        if (reason != lastLogged || now - lastLoggedAt > LOG_REPEAT_MS) {
            log("[WebWalk] hosted planner: $reason; planning locally")
            lastLogged = reason
            lastLoggedAt = now
        }
        return Outcome.Fallback(reason)
    }

    companion object {
        const val ROUTE_PATH = "/route"
        const val BUSY_RETRIES = 2
        const val BREAKER_FAILURES = 3
        const val BREAKER_MS = 60_000L
        const val AUTH_REST_MS = 300_000L
        private const val BUSY_BACKOFF_MS = 250
        private const val BUSY_BACKOFF_VARIANCE = 80 * 80
        private const val BUSY_BACKOFF_MIN_MS = 100L
        private const val BUSY_BACKOFF_MAX_MS = 600L
        private const val LOG_REPEAT_MS = 30_000L
    }
}
