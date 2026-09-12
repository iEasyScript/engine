package org.projectx.core.game.combat.npc.drop

import kotlinx.serialization.Serializable

/** One droppable item. Identified by cache [id] or by [name] (resolved to an obj id at use time). */
@Serializable
data class DropEntry(
    val id: Int? = null,
    val name: String? = null,
    val min: Int = 1,
    val max: Int = 1,
    val weight: Int = 1,
)

/**
 * One drop table: [always] entries always drop; then with probability [chance]/[outOf] one weighted
 * entry is chosen from [drops] (and/or the named shared table [rollTable], e.g. `rdt_standard`).
 */
@Serializable
data class DropTable(
    val chance: Int = 1,
    val outOf: Int = 1,
    val always: List<DropEntry>? = null,
    val rollTable: String? = null,
    val drops: List<DropEntry> = emptyList(),
)

/** Authored drop tables for an NPC (or set of NPCs), keyed by [names] and/or cache [ids]. */
@Serializable
data class DropTableDefinition(
    val names: List<String>? = null,
    val ids: List<Int>? = null,
    val tables: List<DropTable> = emptyList(),
)

/** A concrete rolled drop (item + amount). */
data class RolledDrop(val id: Int?, val name: String?, val amount: Int)
