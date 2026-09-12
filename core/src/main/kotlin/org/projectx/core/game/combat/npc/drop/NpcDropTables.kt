package org.projectx.core.game.combat.npc.drop

import kotlinx.serialization.json.Json
import org.projectx.core.EnvVars
import org.projectx.core.game.combat.npc.jsonFiles
import world.gregs.voidps.cache.type.data.NpcType
import java.io.File
import kotlin.random.Random

/**
 * Registry of authored [DropTableDefinition]s, loaded once from every JSON file under the data dir's
 * `npc/drops` (per-NPC files in category subdirs; files beginning with `_` are skipped). Keyed by
 * cache id and by lowercased name. Death-drop spawning is not wired yet — [roll] exists so authored
 * tables can be validated (e.g. via a `::roll` command). Named shared tables ([DropTable.rollTable])
 * are not resolved yet.
 */
object NpcDropTables {
    private val root = File(EnvVars.dataPath, "npc/drops")
    private val json = Json { ignoreUnknownKeys = true }

    val all: List<DropTableDefinition> by lazy { load() }
    val byId: Map<Int, DropTableDefinition> by lazy {
        HashMap<Int, DropTableDefinition>().apply { for (d in all) d.ids?.forEach { put(it, d) } }
    }
    val byName: Map<String, DropTableDefinition> by lazy {
        HashMap<String, DropTableDefinition>().apply { for (d in all) d.names?.forEach { put(it.lowercase(), d) } }
    }

    fun definitionFor(type: NpcType): DropTableDefinition? =
        byId[type.id] ?: byName[type.name.lowercase()]

    fun roll(type: NpcType, random: Random = Random.Default): List<RolledDrop> =
        definitionFor(type)?.let { roll(it, random) } ?: emptyList()

    fun roll(def: DropTableDefinition, random: Random = Random.Default): List<RolledDrop> {
        val out = ArrayList<RolledDrop>()
        for (table in def.tables) {
            table.always?.forEach { out.add(it.toRolled(random)) }
            if (table.drops.isEmpty()) continue
            if (random.nextInt(table.outOf.coerceAtLeast(1)) < table.chance) {
                pickWeighted(table.drops, random)?.let { out.add(it.toRolled(random)) }
            }
        }
        return out
    }

    private fun pickWeighted(entries: List<DropEntry>, random: Random): DropEntry? {
        val total = entries.sumOf { it.weight.coerceAtLeast(0) }
        if (total <= 0) return entries.firstOrNull()
        var roll = random.nextInt(total)
        for (entry in entries) {
            roll -= entry.weight.coerceAtLeast(0)
            if (roll < 0) return entry
        }
        return entries.last()
    }

    private fun DropEntry.toRolled(random: Random): RolledDrop {
        val amount = if (max > min) random.nextInt(min, max + 1) else min
        return RolledDrop(id, name, amount)
    }

    private fun load(): List<DropTableDefinition> {
        val out = ArrayList<DropTableDefinition>()
        for (file in jsonFiles(root)) {
            try {
                out.add(json.decodeFromString(DropTableDefinition.serializer(), file.readText()))
            } catch (e: Exception) {
                System.err.println("[NpcDropTables] failed to load ${file.path}: ${e.message}")
            }
        }
        println("[NpcDropTables] loaded ${out.size} drop-table definitions")
        return out
    }
}
