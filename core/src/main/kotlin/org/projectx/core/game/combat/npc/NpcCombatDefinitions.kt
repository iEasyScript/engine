package org.projectx.core.game.combat.npc

import kotlinx.serialization.json.Json
import org.projectx.core.EnvVars
import world.gregs.voidps.cache.type.data.NpcType
import java.io.File

/**
 * Registry of authored [NpcCombatDefinition]s, loaded once from every JSON file under the data dir's
 * `npc/combat` (per-NPC files in category subdirs; files beginning with `_` are skipped). Keyed by
 * cache id and by lowercased name; [resolve] merges a matched def over the cache-derived base.
 */
object NpcCombatDefinitions {
    private val root = File(EnvVars.dataPath, "npc/combat")
    private val json = Json { ignoreUnknownKeys = true }

    val all: List<NpcCombatDefinition> by lazy { load() }
    val byId: Map<Int, NpcCombatDefinition> by lazy {
        HashMap<Int, NpcCombatDefinition>().apply { for (d in all) d.ids?.forEach { put(it, d) } }
    }
    val byName: Map<String, NpcCombatDefinition> by lazy {
        HashMap<String, NpcCombatDefinition>().apply { for (d in all) d.names?.forEach { put(it.lowercase(), d) } }
    }

    fun definitionFor(type: NpcType): NpcCombatDefinition? =
        byId[type.id] ?: byName[type.name.lowercase()]

    fun resolve(type: NpcType): ResolvedNpcCombat = ResolvedNpcCombat.from(type, definitionFor(type))

    private fun load(): List<NpcCombatDefinition> {
        val out = ArrayList<NpcCombatDefinition>()
        for (file in jsonFiles(root)) {
            try {
                out.add(json.decodeFromString(NpcCombatDefinition.serializer(), file.readText()))
            } catch (e: Exception) {
                System.err.println("[NpcCombatDefinitions] failed to load ${file.path}: ${e.message}")
            }
        }
        println("[NpcCombatDefinitions] loaded ${out.size} combat definitions")
        return out
    }
}
