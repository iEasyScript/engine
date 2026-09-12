package com.projectx.script.impl.trent.leagues

import world.gregs.voidps.cache.Cache

enum class LeagueTargetKind { LOC, NPC, OBJ }

/**
 * What a task actually points at in the world.
 *
 * A task names a thing, never an id, and one name covers many ids - "yew tree" is a different loc
 * per variant and per placement, and every one of them satisfies the task. Resolution therefore
 * goes name -> every id sharing that name, so the whole variant set can be highlighted rather than
 * an arbitrary representative.
 */
data class LeagueTaskTargets(
    val locIds: List<Int> = emptyList(),
    val npcIds: List<Int> = emptyList(),
    val objIds: List<Int> = emptyList(),
    val matchedNames: List<String> = emptyList(),
) {
    val empty: Boolean get() = locIds.isEmpty() && npcIds.isEmpty() && objIds.isEmpty()
}

/**
 * Matches task text against cache entity names.
 *
 * Scans word n-grams longest-first rather than every known name, so a description costs its own
 * length rather than the size of the name tables. Scenery and npcs are restricted to entities that
 * expose a menu option - a task points at something you can act on, and the un-actionable entries
 * are what turn generic words into false matches.
 */
object LeagueTargetResolver {

    private const val MIN_NAME_LENGTH = 4
    private const val MAX_PHRASE_WORDS = 5

    /** Words that name a real entity but are far more often ordinary prose in a task description. */
    private val ignored = setOf("player", "sail", "miniquest", "quest", "coins", "spine", "chest", "door", "gate")

    private val words = Regex("[a-z0-9']+")

    private val locsByName: Map<String, List<Int>> by lazy {
        index(Cache.locs.filter { it.options?.any { option -> !option.isNullOrBlank() } == true }.map { it.id to it.name })
    }
    private val npcsByName: Map<String, List<Int>> by lazy {
        index(Cache.npcs.filter { it.options?.any { option -> !option.isNullOrBlank() } == true }.map { it.id to it.name })
    }
    private val objsByName: Map<String, List<Int>> by lazy { index(Cache.objs.map { it.id to it.name }) }

    private fun index(entries: List<Pair<Int, String>>): Map<String, List<Int>> = entries
        .filter { (_, name) -> name.isNotBlank() && name != "null" }
        .groupBy({ (_, name) -> name.lowercase() }, { (id, _) -> id })
        .filterKeys { it.length >= MIN_NAME_LENGTH && it !in ignored }
        .mapValues { (_, ids) -> ids.distinct() }

    fun resolve(task: LeagueTask): LeagueTaskTargets {
        val tokens = words.findAll(task.description.lowercase()).map { it.value }.toList()
        if (tokens.isEmpty()) {
            return LeagueTaskTargets()
        }
        val taken = BooleanArray(tokens.size)
        val locs = mutableListOf<Int>()
        val npcs = mutableListOf<Int>()
        val objs = mutableListOf<Int>()
        val matched = mutableListOf<String>()

        for (size in MAX_PHRASE_WORDS downTo 1) {
            for (start in 0..tokens.size - size) {
                if ((start until start + size).any { taken[it] }) continue
                val phrase = tokens.subList(start, start + size).joinToString(" ")
                if (phrase.length < MIN_NAME_LENGTH) continue
                val kind = when {
                    locsByName.containsKey(phrase) -> LeagueTargetKind.LOC
                    npcsByName.containsKey(phrase) -> LeagueTargetKind.NPC
                    objsByName.containsKey(phrase) -> LeagueTargetKind.OBJ
                    else -> continue
                }
                for (i in start until start + size) taken[i] = true
                when (kind) {
                    LeagueTargetKind.LOC -> locs += locsByName.getValue(phrase)
                    LeagueTargetKind.NPC -> npcs += npcsByName.getValue(phrase)
                    LeagueTargetKind.OBJ -> objs += objsByName.getValue(phrase)
                }
                matched += phrase
            }
        }
        return LeagueTaskTargets(locs.distinct(), npcs.distinct(), objs.distinct(), matched)
    }
}
