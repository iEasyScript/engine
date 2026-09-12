package org.projectx.core.game.combat

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.StructType

class EffectType(val structId: Int) {
    private fun struct(): StructType? = Cache.struct(structId)
    private fun param(id: Int): Int = struct()?.getIntValue(id, 0) ?: 0

    val name: String get() = struct()?.getStringValue(CombatIds.ABILITY_NAME) ?: ""
    val iconGraphic: Int get() = param(CombatIds.ABILITY_ICON)
    val isDebuff: Boolean get() = param(CombatIds.BUFF_TYPE) == DEBUFF
    val category: Int get() = param(CombatIds.BUFF_CATEGORY)
    val priority: Int get() = param(CombatIds.BUFF_PRIORITY)

    fun active(ctx: CombatContext = CombatContext.current): Boolean = EffectVarTables.active(structId, ctx)
    fun stacks(ctx: CombatContext = CombatContext.current): Int = EffectVarTables.stacks(structId, ctx)
    fun timeRemainingMs(ctx: CombatContext = CombatContext.current): Long = EffectVarTables.timeRemainingMs(structId, ctx)
    fun activeOnOpponent(ctx: CombatContext = CombatContext.current): Boolean = EffectVarTables.activeOnOpponent(structId, ctx)

    override fun equals(other: Any?): Boolean = other is EffectType && other.structId == structId
    override fun hashCode(): Int = structId

    companion object {
        const val DEBUFF = 1
    }
}
