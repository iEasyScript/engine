package org.projectx.tools.npclocations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

/**
 * Fetches NPC world coordinates from the RS3 wiki and writes them keyed by cache npc id.
 *
 * The cache has no npc coordinates at all - map data names the npc types present in a square but
 * not where they stand - so the wiki is the only bulk source. This runs offline and writes a file
 * the engine reads; the injected client never talks to the wiki itself.
 *
 *   ./gradlew :tools:npcLocationFetch -Pargs="--cache ./data/cache --out ~/.projectx/leagues/npc-locations.json"
 */
private const val API = "https://runescape.wiki/api.php"
private const val USER_AGENT = "project-x npc-location-fetch (offline dataset build)"
private const val BATCH = 50

fun main(args: Array<String>) {
    val options = args.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
    val cachePath = options["--cache"] ?: "./data/cache"
    val out = Path.of((options["--out"] ?: "${System.getProperty("user.home")}/.projectx/leagues/npc-locations.json")
        .replaceFirst("~", System.getProperty("user.home")))
    val limit = options["--limit"]?.toIntOrNull() ?: Int.MAX_VALUE

    Cache.init(SQLiteCache.load(Path.of(cachePath), readOnly = true))
    val named = Cache.npcs
        .filter { it.name.isNotBlank() && it.name != "null" }
        .groupBy { it.name }
    println("Cache exposes ${named.size} distinct npc names")

    val coordinates = LinkedHashMap<Int, List<Triple<Int, Int, Int>>>()
    var queried = 0
    for ((name, types) in named) {
        if (queried >= limit) break
        queried++
        val tiles = runCatching { fetchCoordinates(name) }.getOrElse {
            System.err.println("  $name: ${it.message}")
            emptyList()
        }
        if (tiles.isEmpty()) continue
        for (type in types) {
            coordinates[type.id] = tiles
        }
        if (queried % 100 == 0) println("  $queried names queried, ${coordinates.size} npc ids located")
    }

    val document = buildJsonObject {
        put("source", "https://runescape.wiki")
        putJsonObject("locations") {
            for ((id, tiles) in coordinates) {
                putJsonArray(id.toString()) {
                    for ((x, y, level) in tiles) {
                        add(buildJsonObject {
                            put("x", x)
                            put("y", y)
                            put("level", level)
                        })
                    }
                }
            }
        }
    }
    Files.createDirectories(out.parent)
    Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), document))
    println("Wrote ${coordinates.size} npc ids to $out")
}

/**
 * The wiki records npc placements as `Location` coordinate properties on the npc's page. Anything
 * without them simply yields no tiles rather than an error.
 */
private fun fetchCoordinates(name: String): List<Triple<Int, Int, Int>> {
    val query = "$API?action=askargs&format=json&conditions=" +
        URLEncoder.encode(name, StandardCharsets.UTF_8) +
        "&printouts=" + URLEncoder.encode("Location JSON", StandardCharsets.UTF_8)
    val connection = URI(query).toURL().openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", USER_AGENT)
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    try {
        if (connection.responseCode != 200) return emptyList()
        val body = connection.inputStream.readAllBytes().decodeToString()
        val results = Json.parseToJsonElement(body).jsonObject["query"]?.jsonObject
            ?.get("results")?.jsonObject ?: return emptyList()
        val tiles = mutableListOf<Triple<Int, Int, Int>>()
        for ((_, page) in results) {
            val printouts = page.jsonObject["printouts"]?.jsonObject ?: continue
            for ((_, values) in printouts) {
                for (value in values.jsonArray) {
                    val text = value.jsonPrimitive.contentOrNullSafe() ?: continue
                    tiles += parseLocations(text)
                }
            }
        }
        return tiles.distinct()
    } finally {
        connection.disconnect()
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

private fun parseLocations(text: String): List<Triple<Int, Int, Int>> {
    val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
    val objects = when {
        element is JsonObject -> listOf(element)
        else -> runCatching { element.jsonArray.map { it.jsonObject } }.getOrElse { return emptyList() }
    }
    return objects.mapNotNull { entry ->
        val x = entry["x"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
        val y = entry["y"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
        val level = entry["z"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        Triple(x, y, level)
    }
}
