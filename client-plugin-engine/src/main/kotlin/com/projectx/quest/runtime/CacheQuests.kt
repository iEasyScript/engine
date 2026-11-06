package com.projectx.quest.runtime

import com.projectx.script.api.varps
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.QuestType

/** Quest completion as the game cache defines it: every progress var of the quest at its completed value. */
internal object CacheQuests {
    @Volatile
    private var byName: Map<String, QuestType>? = null

    fun completed(id: Int): Boolean = Cache.quest(id)?.let(::completed) == true

    /** By the name the quest journal shows, ignoring case, punctuation and a leading "The". */
    fun completed(name: String): Boolean = named(name)?.let(::completed) == true

    fun sameName(a: String, b: String): Boolean = key(a) == key(b)

    private fun completed(quest: QuestType): Boolean {
        val progress = quest.varpProgress.orEmpty().map { varps.getVar(it.varId) >= it.completedValue } +
            quest.varbitProgress.orEmpty().map { varps.getVarBit(it.varId) >= it.completedValue }
        return progress.isNotEmpty() && progress.all { it }
    }

    private fun named(name: String): QuestType? {
        val all = byName ?: Cache.quests.mapNotNull { quest -> quest.name?.takeIf { it.isNotBlank() }?.let { key(it) to quest } }.toMap()
            .also { if (it.isNotEmpty()) byName = it }
        return all[key(name)]
    }

    private fun key(name: String): String = name.lowercase().removePrefix("the ").filter { it.isLetterOrDigit() }
}
