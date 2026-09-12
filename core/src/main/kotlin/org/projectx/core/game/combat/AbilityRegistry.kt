package org.projectx.core.game.combat

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval

object AbilityRegistry {
    private val sourceEnums: IntArray by lazy {
        intArrayOf(
            CombatIds.ENUM_MELEE, CombatIds.ENUM_RANGED, CombatIds.ENUM_MAGIC,
            CombatIds.ENUM_NECROMANCY, CombatIds.ENUM_DEFENCE, CombatIds.ENUM_CONSTITUTION,
            CombatIds.ENUM_PRAYER, CombatIds.ENUM_ANCIENT, CombatIds.ENUM_SUMMONING, CombatIds.ENUM_EMOTES,
        )
    }
    private val dummyStructs: IntArray by lazy {
        intArrayOf(
            CombatIds.STRUCT_SPECIAL_ATTACK, CombatIds.STRUCT_HP_BUTTON, CombatIds.STRUCT_WORN_SLOT,
            CombatIds.STRUCT_AUTO_RETALIATE, CombatIds.STRUCT_OVERHEAD_EMOTE,
        )
    }

    val all: List<AbilityType> by lazy { build() }
    val byStructId: Map<Int, AbilityType> by lazy { all.associateBy { it.structId } }
    val byAbilityId: Map<Int, AbilityType> by lazy { all.filter { it.abilityId != 0 }.associateBy { it.abilityId } }
    val byName: Map<String, AbilityType> by lazy {
        buildMap { for (ability in all) Gameval.struct(ability.structId)?.let { put(it, ability) } }
    }

    operator fun get(structId: Int): AbilityType? = byStructId[structId]
    fun byCacheName(name: String): AbilityType? = byName[name]

    private fun build(): List<AbilityType> {
        val ids = LinkedHashSet<Int>()
        for (enumId in sourceEnums) {
            Cache.enum(enumId)?.values?.values?.forEach { value -> (value as? Int)?.let { ids += it } }
        }
        dummyStructs.forEach { ids += it }
        return ids.filter { Cache.struct(it) != null }.map { AbilityType(it) }
    }
}
