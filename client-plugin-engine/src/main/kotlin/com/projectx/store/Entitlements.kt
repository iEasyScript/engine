package com.projectx.store

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.script.Script
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

/** Decides whether a paid store script may run; free and locally built scripts are never gated. */
object Entitlements {

    private const val ENFORCE_SECONDS = 60L
    private const val REFRESH_SECONDS = 5L * 60L
    private const val GAME = "rs3"

    // Two refresh intervals and some slack, so one dropped request does not end a trial.
    private val TRIAL_GRACE_NANOS = TimeUnit.SECONDS.toNanos(2 * REFRESH_SECONDS + 30)

    enum class Check { ANSWERED, REJECTED, UNREACHABLE }

    private val expiries = ConcurrentHashMap<String, Instant>()
    private val trialDeadlines = ConcurrentHashMap<String, Long>()
    private val paid = ConcurrentHashMap.newKeySet<String>()
    private val storeUrls = ConcurrentHashMap<String, String>()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var lastAnsweredNanos = Long.MIN_VALUE / 2
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

    private fun storeBase(): String =
        System.getenv("PROJECTX_STORE_URL")?.takeIf { it.isNotBlank() } ?: "https://xclient.dev"

    private fun token(): String? = runCatching {
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

    fun isPaid(name: String): Boolean = paid.contains(name)

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

        worker.submit {
            val check = runCatching { refresh() }.getOrDefault(Check.UNREACHABLE)
            if (isEntitled(name)) {
                println("[store] access to $name confirmed; start it again.")
                return@submit
            }
            if (warned.add(name)) {
                when (check) {
                    // Do not send somebody to buy what they may already own.
                    Check.UNREACHABLE -> println(
                        "[store] $name could not start: the store could not be reached to check your access."
                    )
                    else -> println(
                        if (token() == null) "[store] $name is a paid script: sign in with Discord in the launcher's Store tab to run it."
                        else "[store] $name needs active access to run. ${storeUrls[name] ?: storeBase()}"
                    )
                }
            }
        }
        return false
    }

    private fun enforce() {
        for (script in ScriptExecutor.activeScripts.toList()) {
            val name = nameOf(script)
            if (isPaid(name) && !isEntitled(name)) {
                println("[store] access to $name has ended; stopping it.")
                runCatching { script.stop() }
            }
        }
    }

    fun refresh(): Check {
        val catalogue = catalogueFor(GAME) ?: return Check.UNREACHABLE
        val jars = ArrayList<StoreJar>()
        runCatching {
            val fresh = HashSet<String>()
            JsonParser.parseString(catalogue).asJsonArray.forEach { entry ->
                val obj = entry.asJsonObject
                val name = obj.get("internalName")?.asString ?: return@forEach
                if (obj.get("paid")?.asBoolean != true) return@forEach
                fresh.add(name)
                obj.text("storeUrl")?.let { storeUrls[name] = it }
                val version = obj.text("version")
                val url = obj.text("downloadUrl")
                if (version != null && url != null) {
                    jars += StoreJar(name, version, url, obj.text("sha256"), obj.text("fileName"))
                }
            }
            paid.retainAll(fresh)
            paid.addAll(fresh)
            rememberPaid()
        }

        val token = token() ?: run {
            // Signed out is an answer: nothing is owned.
            expiries.clear()
            trialDeadlines.clear()
            lastAnsweredNanos = System.nanoTime()
            return Check.ANSWERED
        }

        val body = get("${storeBase()}/api/v1/entitlements?game=$GAME", token) ?: return Check.UNREACHABLE
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

    private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString

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

    private fun get(url: String, token: String? = null): String? = runCatching {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET()
        if (token != null) builder.header("Authorization", "Bearer $token")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        response.body()
    }.getOrNull()
}
