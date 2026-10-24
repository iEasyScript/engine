package com.projectx.store

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the store says about one of its scripts, for the overlay to show beside it. [until] is when access ends,
 * or null when there is none.
 */
data class StoreAccess(
    val free: Boolean,
    val storeUrl: String,
    val entitled: Boolean,
    val until: Instant?,
    val signedIn: Boolean,
    val prices: StorePrices?,
)

/** What each tier costs in X Tokens, sale included, as the store quotes it; [salePercent] is zero with no sale on. */
data class StorePrices(val week: Int, val fortnight: Int, val month: Int, val salePercent: Int) {
    /** The store card's line: the cheapest way in. */
    fun summary(free: Boolean): String =
        if (free) "Free" else "From ${minOf(week, fortnight, month)} X Tokens" + if (salePercent > 0) " ($salePercent% off)" else ""

    fun tiers(): String = "$week X a week, $fortnight X a fortnight, $month X a month"
}

/**
 * Decides whether a store script may run. Every script the store lists needs the player subscribed to it, free
 * ones included; a script the store does not list - one the player built or was given - is never gated.
 */
object Entitlements {

    private const val ENFORCE_SECONDS = 60L
    private const val REFRESH_SECONDS = 5L * 60L
    private const val GAME = "rs3"

    // Two refresh intervals and some slack, so one dropped request does not end a trial.
    private val TRIAL_GRACE_NANOS = TimeUnit.SECONDS.toNanos(2 * REFRESH_SECONDS + 30)

    enum class Check { ANSWERED, REJECTED, UNREACHABLE, SIGN_IN_REJECTED }

    // A store that cannot be reached is asked again before a start is refused, so one dropped request does not
    // turn away a script the player owns.
    private val START_RETRY_SECONDS = longArrayOf(0, 2, 5)

    private val expiries = ConcurrentHashMap<String, Instant>()
    private val trialDeadlines = ConcurrentHashMap<String, Long>()
    private val paid = ConcurrentHashMap.newKeySet<String>()
    private val free = ConcurrentHashMap.newKeySet<String>()
    private val prices = ConcurrentHashMap<String, StorePrices>()
    private val storeUrls = ConcurrentHashMap<String, String>()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var lastAnsweredNanos = Long.MIN_VALUE / 2
    @Volatile private var signedIn = false
    @Volatile private var started = false

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    private val worker by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "projectx-entitlements").apply { isDaemon = true }
        }
    }

    private fun home(): Path = Path.of(System.getProperty("user.home"), ".projectx")

    internal fun storeBase(): String =
        System.getenv("PROJECTX_STORE_URL")?.takeIf { it.isNotBlank() } ?: "https://xclient.dev"

    internal fun token(): String? = runCatching {
        val file = home().resolve("store-token.json")
        if (!Files.exists(file)) return null
        JsonParser.parseString(Files.readString(file)).asJsonObject.get("token")?.asString
    }.getOrNull()

    // Remembered so a store that is unreachable at startup cannot unlock a paid script.
    private fun paidCache(): Path = home().resolve("paid-scripts.json")

    internal fun encodePaid(names: Collection<String>): String =
        JsonArray().apply { names.forEach { add(it) } }.toString()

    internal fun decodePaid(text: String): Set<String> =
        JsonParser.parseString(text).asJsonArray.map { it.asString }.toSet()

    private fun rememberPaid() = runCatching {
        Files.createDirectories(home())
        Files.writeString(paidCache(), encodePaid(paid))
    }

    private fun recallPaid() = runCatching {
        val file = paidCache()
        if (!Files.exists(file)) return@runCatching
        paid.addAll(decodePaid(Files.readString(file)))
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        recallPaid()
        worker.scheduleWithFixedDelay({ runCatching { refresh() } }, 0, REFRESH_SECONDS, TimeUnit.SECONDS)
        worker.scheduleWithFixedDelay({ runCatching { enforce() } }, ENFORCE_SECONDS, ENFORCE_SECONDS, TimeUnit.SECONDS)
    }

    /** True for every script the store lists, free or paid: the ones that need a subscription to run. */
    fun isPaid(name: String): Boolean = paid.contains(name)

    /** Null for a script the store does not list. */
    fun accessOf(name: String): StoreAccess? {
        if (!isPaid(name)) return null
        return StoreAccess(
            free = name in free,
            storeUrl = storeUrls[name] ?: storeBase(),
            entitled = isEntitled(name),
            until = expiries[name],
            signedIn = signedIn,
            prices = prices[name],
        )
    }

    fun isEntitled(name: String): Boolean = entitled(
        expiry = expiries[name],
        trialEndNanos = trialDeadlines[name],
        now = Instant.now(),
        nowNanos = System.nanoTime(),
        lastAnsweredNanos = lastAnsweredNanos,
    )

    // A trial outranks paid access: it runs on the monotonic clock and lapses when the store goes quiet,
    // whereas paid access deliberately outlives an outage.
    internal fun entitled(
        expiry: Instant?,
        trialEndNanos: Long?,
        now: Instant,
        nowNanos: Long,
        lastAnsweredNanos: Long,
    ): Boolean {
        if (trialEndNanos != null) {
            return nowNanos - trialEndNanos < 0 && nowNanos - lastAnsweredNanos <= TRIAL_GRACE_NANOS
        }
        return expiry != null && now.isBefore(expiry)
    }

    fun nameOf(script: Script): String = script.javaClass.simpleName

    // Reads the cache only; a refusal is re-checked in the background so a purchase just made starts
    // without a restart.
    fun mayStart(script: Script): Boolean {
        val name = nameOf(script)
        if (!isPaid(name) || isEntitled(name)) return true

        val title = script.javaClass.getAnnotation(ScriptDescription::class.java)?.name ?: name
        StoreNotices.refused(title, accessOf(name), StartRefusal.Reason.CHECKING)
        worker.submit {
            val check = checkPatiently()
            if (isEntitled(name)) {
                println("[store] access to $name confirmed; start it again.")
                StoreNotices.refused(title, accessOf(name), StartRefusal.Reason.CONFIRMED)
                return@submit
            }
            val reason = when {
                check == Check.UNREACHABLE -> StartRefusal.Reason.UNREACHABLE
                check == Check.SIGN_IN_REJECTED -> StartRefusal.Reason.SIGN_IN_EXPIRED
                token() == null -> StartRefusal.Reason.SIGNED_OUT
                else -> StartRefusal.Reason.NOT_OWNED
            }
            StoreNotices.refused(title, accessOf(name), reason)
            if (warned.add(name)) println(refusal(name, check))
        }
        return false
    }

    private fun checkPatiently(): Check {
        var check = Check.UNREACHABLE
        for (wait in START_RETRY_SECONDS) {
            if (wait > 0) Thread.sleep(TimeUnit.SECONDS.toMillis(wait))
            check = runCatching { refresh() }.getOrDefault(Check.UNREACHABLE)
            if (check != Check.UNREACHABLE) break
        }
        return check
    }

    internal fun refusal(name: String, check: Check): String {
        val url = storeUrls[name] ?: storeBase()
        return when {
            // Do not send somebody to the store for what they may already have.
            check == Check.UNREACHABLE -> "[store] $name could not start: the store could not be reached to check your access."
            check == Check.SIGN_IN_REJECTED -> "[store] $name could not start: the store no longer accepts this launcher's sign-in. " +
                "Sign out and back in on the launcher's Store tab, then start it again."
            token() == null -> "[store] $name is a store script: sign in with Discord in the launcher's Store tab, then " +
                (if (name in free) "get it free on the store. $url" else "subscribe on the store. $url")
            name in free -> "[store] $name is free, but you need to get it on the store to run it. $url"
            else -> "[store] $name needs an active subscription to run. $url"
        }
    }

    private fun enforce() {
        for (script in ScriptExecutor.activeScripts.toList()) {
            val name = nameOf(script)
            if (isPaid(name) && !isEntitled(name)) {
                println(
                    if (name in free) "[store] your free access to $name has run out; renew it free on the store. ${storeUrls[name] ?: storeBase()}"
                    else "[store] access to $name has ended; stopping it."
                )
                runCatching { script.stop() }
            }
        }
    }

    fun refresh(): Check {
        val catalogue = catalogueFor(GAME) ?: return Check.UNREACHABLE
        val jars = ArrayList<StoreJar>()
        runCatching {
            val fresh = HashSet<String>()
            val freshFree = HashSet<String>()
            val freshPrices = HashMap<String, StorePrices>()
            JsonParser.parseString(catalogue).asJsonArray.forEach { entry ->
                val obj = entry.asJsonObject
                val name = obj.get("internalName")?.asString ?: return@forEach
                if (!requiresSubscription(obj)) return@forEach
                fresh.add(name)
                if (obj.flag("free")) freshFree.add(name)
                pricesOf(obj)?.let { freshPrices[name] = it }
                obj.text("storeUrl")?.let { storeUrls[name] = it }
                val version = obj.text("version")
                val url = obj.text("downloadUrl")
                if (version != null && url != null) {
                    jars += StoreJar(name, version, url, obj.text("sha256"), obj.text("fileName"))
                }
            }
            paid.retainAll(fresh)
            paid.addAll(fresh)
            free.retainAll(freshFree)
            free.addAll(freshFree)
            prices.keys.retainAll(freshPrices.keys)
            prices.putAll(freshPrices)
            rememberPaid()
        }

        val token = token()
        signedIn = token != null
        if (token == null) {
            // Signed out is an answer: nothing is owned.
            expiries.clear()
            trialDeadlines.clear()
            lastAnsweredNanos = System.nanoTime()
            return Check.ANSWERED
        }

        val reply = get("${storeBase()}/api/v1/entitlements?game=$GAME", token) ?: return Check.UNREACHABLE
        val check = checkFor(reply.status)
        if (check == Check.SIGN_IN_REJECTED) {
            signInRejected()
            return check
        }
        if (check != Check.ANSWERED) return check
        val body = reply.body
        warned.remove(SIGN_IN_WARNING)
        return runCatching {
            expiries.clear()
            trialDeadlines.clear()
            val root = JsonParser.parseString(body)
            val list = if (root.isJsonArray) root.asJsonArray else root.asJsonObject.getAsJsonArray("entitlements")
            list?.forEach { entry ->
                val obj = entry.asJsonObject
                val name = obj.get("internalName")?.asString ?: return@forEach
                val endsAt = obj.get("expiresAt")?.takeUnless { it.isJsonNull }?.asString?.let { Instant.parse(it) }
                    ?: return@forEach
                expiries[name] = endsAt
                if (obj.get("trial")?.asBoolean == true) {
                    trialDeadlines[name] = System.nanoTime() + Duration.between(Instant.now(), endsAt).toNanos()
                }
            }
            lastAnsweredNanos = System.nanoTime()
            runCatching { ScriptSync.sync(expiries.keys.toSet(), jars, token, http, home().resolve("scripts")) }
            Check.ANSWERED
        }.getOrDefault(Check.REJECTED)
    }

    /** What an entitlements answer with [status] means; a refused token is a sign-in to redo, not an outage. */
    internal fun checkFor(status: Int): Check = when (status) {
        in 200..299 -> Check.ANSWERED
        401, 403 -> Check.SIGN_IN_REJECTED
        else -> Check.UNREACHABLE
    }

    private const val SIGN_IN_WARNING = "sign-in"

    // The token is dead, so nothing it vouched for is owned any more.
    private fun signInRejected() {
        signedIn = false
        expiries.clear()
        trialDeadlines.clear()
        lastAnsweredNanos = System.nanoTime()
        if (warned.add(SIGN_IN_WARNING)) {
            println("[store] the store no longer accepts this launcher's sign-in; sign out and back in on the launcher's Store tab.")
        }
    }

    private class Reply(val status: Int, val body: String)

    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.flag(key: String): Boolean = get(key)?.takeUnless { it.isJsonNull }?.asBoolean == true

    internal fun pricesOf(entry: JsonObject): StorePrices? = runCatching {
        val tiers = entry.getAsJsonObject("prices") ?: return null
        StorePrices(
            week = tiers.get("week").asInt,
            fortnight = tiers.get("fortnight").asInt,
            month = tiers.get("month").asInt,
            salePercent = entry.get("salePercent")?.takeUnless { it.isJsonNull }?.asInt ?: 0,
        )
    }.getOrNull()

    // A store older than free subscriptions marks only what it sells, as `paid`.
    internal fun requiresSubscription(entry: JsonObject): Boolean = entry.flag("paid") || entry.flag("requiresSubscription")

    // A store older than the game split ignores ?game= and sends the OSRS list, so an unlabelled answer
    // counts as no answer and nothing is gated.
    private fun catalogueFor(game: String): String? = runCatching {
        val url = "${storeBase()}/api/v1/hub/plugins.json?game=$game"
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        val answered = response.headers().firstValue("X-ProjectX-Game").orElse(null)
        if (answered != game) {
            if (warned.add("catalogue")) {
                println("[store] the store did not answer for $game (said ${answered ?: "nothing"}); not gating anything.")
            }
            return null
        }
        response.body()
    }.getOrNull()

    private fun get(url: String, token: String? = null): Reply? = runCatching {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET()
        if (token != null) builder.header("Authorization", "Bearer $token")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        Reply(response.statusCode(), response.body())
    }.getOrNull()
}
