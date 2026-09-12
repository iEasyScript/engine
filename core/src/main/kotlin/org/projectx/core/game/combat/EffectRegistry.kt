package org.projectx.core.game.combat

import world.gregs.voidps.cache.Cache

object EffectRegistry {
    val all: List<EffectType> by lazy { build() }
    val byStructId: Map<Int, EffectType> by lazy { all.associateBy { it.structId } }

    operator fun get(structId: Int): EffectType? = byStructId[structId]

    private fun build(): List<EffectType> {
        val ids = LinkedHashSet<Int>()
        EffectStructs.ALL.forEach { ids += it }
        Cache.enum(CombatIds.ENUM_ALL_BUFFS_AND_DEBUFFS)?.values?.keys?.forEach { ids += it }
        return ids.map { EffectType(it) }
    }
}
