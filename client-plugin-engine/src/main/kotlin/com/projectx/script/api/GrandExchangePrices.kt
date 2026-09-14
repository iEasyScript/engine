package com.projectx.script.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.projectx.script.Script
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/** The most recent instant-buy ([high]) and instant-sell ([low]) trade prices, with when each happened (epoch seconds). */
data class GrandExchangePrice(
    val itemId: Int,
    val high: Long?,
    val highTime: Long?,
    val low: Long?,
    val lowTime: Long?,
) {
    /** What an instant buy then instant sell would have earned per item, before any tax. */
    val margin: Long? get() = if (high != null && low != null) high - low else null
}

data class GrandExchangeItem(
    val id: Int,
    val name: String,
    val members: Boolean,
    /** Most of this item that can be bought every four hours, or null when the wiki does not know it. */
    val buyLimit: Int?,
    val value: Long?,
    val highAlch: Long?,
    val lowAlch: Long?,
)

/**
 * Live RuneScape 3 Grand Exchange prices from the RuneScape Wiki's real-time price API
 * (`https://prices.runescape.wiki/rs`).
 *
 * Every lookup reads a local snapshot and never blocks the game: call [refresh] (or `awaitGrandExchangePrices` from a
 * script) to fetch, then read. Refreshes are rate limited to one per [MIN_REFRESH_MILLIS]; the item list is only
 * fetched once per session because it rarely changes.
 */
object GrandExchangePrices {
    const val MIN_REFRESH_MILLIS = 60_000L

    private const val BASE_URL = "https://prices.runescape.wiki/api/v2/rs"
    private const val DEFAULT_USER_AGENT = "ProjectX-script-api GrandExchangePrices (iEasyScript)"

    private data class Snapshot(
        val items: Map<Int, GrandExchangeItem>,
        val itemsByName: Map<String, GrandExchangeItem>,
        val prices: Map<Int, GrandExchangePrice>,
        val volumes: Map<Int, Long>,
        val updatedAtMillis: Long,
    )

    private val snapshot = AtomicReference(Snapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0L))
    private val inFlight = AtomicReference<CompletableFuture<Boolean>?>(null)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    /**
     * Identifies the script to the wiki, which asks every API user for a descriptive User-Agent. Set it to your
     * script's name and a way to reach you.
     */
    @JvmStatic
    @Volatile
    var userAgent: String = DEFAULT_USER_AGENT

    /** Epoch milliseconds of the last successful refresh, or 0 before the first. */
    @JvmStatic
    val lastUpdatedMillis: Long
        get() = snapshot.get().updatedAtMillis

    @JvmStatic
    val isLoaded: Boolean
        get() = snapshot.get().updatedAtMillis > 0L

    @JvmStatic
    fun price(itemId: Int): GrandExchangePrice? = snapshot.get().prices[itemId]

    @JvmStatic
    fun item(itemId: Int): GrandExchangeItem? = snapshot.get().items[itemId]

    /** Exact, case-insensitive name match. */
    @JvmStatic
    fun item(name: String): GrandExchangeItem? = snapshot.get().itemsByName[name.lowercase()]

    /** Units traded over the last day. */
    @JvmStatic
    fun dailyVolume(itemId: Int): Long? = snapshot.get().volumes[itemId]

    @JvmStatic
    fun items(): Collection<GrandExchangeItem> = snapshot.get().items.values

    @JvmStatic
    fun prices(): Collection<GrandExchangePrice> = snapshot.get().prices.values

    /**
     * Fetches the latest prices and volumes, plus the item list the first time. Completes with true on success.
     * A call inside [MIN_REFRESH_MILLIS] of the last refresh, or while one is running, returns that refresh
     * instead of starting another.
     */
    @JvmStatic
    fun refresh(): CompletableFuture<Boolean> {
        inFlight.get()?.let { return it }
        val current = snapshot.get()
        if (current.updatedAtMillis > 0L && System.currentTimeMillis() - current.updatedAtMillis < MIN_REFRESH_MILLIS) {
            return CompletableFuture.completedFuture(true)
        }
        val started = CompletableFuture<Boolean>()
        if (!inFlight.compareAndSet(null, started)) return inFlight.get() ?: CompletableFuture.completedFuture(isLoaded)

        val itemsFuture = if (current.items.isEmpty()) fetch("mapping").thenApply(::parseItems)
        else CompletableFuture.completedFuture(current.items)

        itemsFuture
            .thenCombine(fetch("latest").thenApply(::parsePrices)) { items, prices -> items to prices }
            .thenCombine(fetch("volumes").thenApply(::parseVolumes)) { (items, prices), volumes ->
                Snapshot(
                    items = items,
                    itemsByName = items.values.associateBy { it.name.lowercase() },
                    prices = prices,
                    volumes = volumes,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            .whenComplete { fresh, error ->
                if (fresh != null) snapshot.set(fresh)
                if (error != null) println("[GrandExchangePrices] refresh failed: ${error.cause?.message ?: error.message}")
                inFlight.set(null)
                started.complete(fresh != null)
            }
        return started
    }

    private fun fetch(endpoint: String): CompletableFuture<JsonElement> {
        val request = HttpRequest.newBuilder(URI.create("$BASE_URL/$endpoint"))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .GET()
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
            check(response.statusCode() == 200) { "$endpoint returned HTTP ${response.statusCode()}" }
            JsonParser.parseString(response.body())
        }
    }

    private fun parseItems(json: JsonElement): Map<Int, GrandExchangeItem> =
        json.asJsonArray.mapNotNull { element ->
            val row = element.asJsonObject
            val id = row.intOrNull("id") ?: return@mapNotNull null
            GrandExchangeItem(
                id = id,
                name = row.get("name")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null,
                members = row.get("members")?.takeUnless { it.isJsonNull }?.asBoolean ?: false,
                buyLimit = row.intOrNull("limit"),
                value = row.longOrNull("value"),
                highAlch = row.longOrNull("highalch"),
                lowAlch = row.longOrNull("lowalch"),
            )
        }.associateBy { it.id }

    private fun parsePrices(json: JsonElement): Map<Int, GrandExchangePrice> =
        json.asJsonObject.getAsJsonObject("data").entrySet().mapNotNull { (key, value) ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            val row = value.asJsonObject
            GrandExchangePrice(id, row.longOrNull("high"), row.longOrNull("highTime"), row.longOrNull("low"), row.longOrNull("lowTime"))
        }.associateBy { it.itemId }

    private fun parseVolumes(json: JsonElement): Map<Int, Long> =
        json.asJsonObject.getAsJsonObject("data").entrySet().mapNotNull { (key, value) ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            if (value.isJsonNull) null else id to value.asLong
        }.toMap()

    private fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt
    private fun JsonObject.longOrNull(name: String): Long? = get(name)?.takeUnless { it.isJsonNull }?.asLong
}

/** Refreshes [GrandExchangePrices] and waits for it, up to [timeoutMillis]. Returns whether prices are loaded. */
suspend fun Script.awaitGrandExchangePrices(timeoutMillis: Long = 20_000): Boolean {
    val refresh = GrandExchangePrices.refresh()
    delayUntil(timeoutMillis) { refresh.isDone }
    return runCatching { refresh.getNow(false) }.getOrDefault(false) || GrandExchangePrices.isLoaded
}
