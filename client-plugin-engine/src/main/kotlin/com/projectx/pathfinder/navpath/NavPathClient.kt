package com.projectx.pathfinder.navpath

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import world.gregs.voidps.type.Tile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal data class NavReply(val status: Int, val body: String)

/** Sends one request and returns the reply; throws on a connection failure or a timeout. */
internal fun interface NavTransport {
    fun post(url: String, token: String, body: String): NavReply
}

/** Everything a `/route` request carries, gathered on the game thread so the planner thread reads no game state. */
internal data class NavRequest(
    val start: Tile,
    val goal: Tile,
    val requirements: List<Pair<String, Int>>,
    val surge: Boolean,
    val surgeCharges: Int,
    val dive: Boolean,
    val diveAvailableInMs: Long,
    val seed: Int,
) {
    fun toJson(): String {
        val body = JsonObject()
        body.add("start", tile(start))
        body.add("goal", tile(goal))
        body.add("profile", JsonObject().apply {
            add("requirements", JsonArray().apply {
                for ((key, value) in requirements) add(JsonObject().apply {
                    addProperty("key", key)
                    addProperty("value", value)
                })
            })
        })
        body.add("options", JsonObject().apply {
            addProperty("return_geometry", false)
            addProperty("only_actions", true)
        })
        body.add("surge", JsonObject().apply {
            addProperty("enabled", surge)
            addProperty("charges", surgeCharges)
            addProperty("cooldown_ms", ABILITY_COOLDOWN_MS)
        })
        body.add("dive", JsonObject().apply {
            addProperty("enabled", dive)
            addProperty("available_in_ms", diveAvailableInMs)
            addProperty("cooldown_ms", ABILITY_COOLDOWN_MS)
        })
        body.addProperty("seed", seed)
        return body.toString()
    }

    private fun tile(tile: Tile) = JsonObject().apply {
        addProperty("wx", tile.x)
        addProperty("wy", tile.y)
        addProperty("plane", tile.plane)
    }

    companion object {
        const val ABILITY_COOLDOWN_MS = 20_400L
    }
}

/** HTTP for the hosted planner: a short connect timeout and a short whole-request one, so an outage costs seconds. */
internal object NavHttp : NavTransport {
    private const val CONNECT_TIMEOUT_MS = 1_500L
    private const val REQUEST_TIMEOUT_MS = 3_000L

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS)).build()
    }

    override fun post(url: String, token: String, body: String): NavReply {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return NavReply(response.statusCode(), response.body().orEmpty())
    }
}
