package org.projectx.core.game.combat

import world.gregs.voidps.cache.Cache

object EffectRegistry {
    val all: List<EffectType> by lazy { build() }
    val byStructId: Map<Int, EffectType> by lazy { all.associateBy { it.structId } }

    /** Indexed by [CombatNames.normalise], so the name the buff bar draws finds the struct whatever form it holds. */
    val byName: Map<String, EffectType> by lazy {
        all.filter { it.name.isNotBlank() }.associateBy { CombatNames.normalise(it.name) }
    }

    operator fun get(structId: Int): EffectType? = byStructId[structId]

    /** The effect the buff bar draws as [name], matched the way [CombatNames.normalise] folds names. */
    fun byDisplayName(name: String): EffectType? = byName[CombatNames.normalise(name)]

    private fun build(): List<EffectType> {
        val ids = LinkedHashSet<Int>()
        EffectStructs.ALL.forEach { ids += it }
        Cache.enum(CombatIds.ENUM_ALL_BUFFS_AND_DEBUFFS)?.values?.keys?.forEach { ids += it }
        return ids.map { EffectType(it) }
    }
}
