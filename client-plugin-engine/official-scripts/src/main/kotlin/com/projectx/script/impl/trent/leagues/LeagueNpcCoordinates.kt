package com.projectx.script.impl.trent.leagues

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.projectx.core.Logger.logWarn
import world.gregs.voidps.type.Tile
import java.io.File

/**
 * Precise NPC tiles, which the cache cannot supply - its map data lists the npc types belonging to
 * a map square but never their coordinates. Populated offline by `:tools:npcLocationFetch` from the
 * RS3 wiki and read here; an absent file simply means no precise tiles, and callers fall back to
 * the map-square granularity [LeagueWorldIndex] provides.
 */
object LeagueNpcCoordinates {

    private val byNpcId: Map<Int, List<Tile>> by lazy { load() }

    val loaded: Boolean get() = byNpcId.isNotEmpty()

    fun tilesOf(npcId: Int): List<Tile> = byNpcId[npcId].orEmpty()

    fun nearest(npcIds: Collection<Int>, from: Tile): Tile? = npcIds
        .flatMap { tilesOf(it) }
        .minByOrNull { it.distanceTo(from) }

    private fun load(): Map<Int, List<Tile>> {
        val file = File(path())
        if (!file.isFile) {
            return emptyMap()
        }
        return try {
            val locations = Json.parseToJsonElement(file.readText()).jsonObject["locations"]?.jsonObject
                ?: return emptyMap()
            locations.mapNotNull { (id, coordinates) ->
                val npcId = id.toIntOrNull() ?: return@mapNotNull null
                npcId to coordinates.jsonArray.mapNotNull { it.toTile() }
            }.toMap()
        } catch (e: Exception) {
            logWarn("Could not read npc locations from ${file.path}: ${e.message}")
            emptyMap()
        }
    }

    private fun kotlinx.serialization.json.JsonElement.toTile(): Tile? {
        val entry = jsonObject
        val x = entry["x"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val y = entry["y"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val level = entry["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return Tile(x, y, level)
    }

    private fun path(): String = System.getenv("LEAGUE_NPC_LOCATIONS")
        ?: "${System.getProperty("user.home")}/.projectx/leagues/npc-locations.json"
}
