package org.projectx.core.game.combat

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.StructType

class AbilityType(val structId: Int) {
    private fun struct(): StructType? = Cache.struct(structId)
    private fun param(id: Int): Int = struct()?.getIntValue(id, 0) ?: 0

    val abilityId: Int get() = param(CombatIds.ABILITY_ID)
    val name: String get() = struct()?.getStringValue(CombatIds.ABILITY_NAME) ?: ""
    val info: String? get() = struct()?.getStringValue(CombatIds.ABILITY_INFO)
    val cooldownParamTicks: Int get() = param(CombatIds.ABILITY_COOLDOWN)
    val durationTicks: Int get() = param(CombatIds.ABILITY_DURATION)
    val adrenalineReq: Int get() = param(CombatIds.ABILITY_ADRENALINE_REQ)
    val adrenalineType: Int get() = param(CombatIds.ABILITY_ADRENALINE_TYPE)
    val adrenalineTier: AdrenalineType? get() = AdrenalineType.forId(adrenalineType)
    val stuns: Boolean get() = param(CombatIds.ABILITY_STUNS) == 1

    /** Seq the caster plays; -1 when the ability keeps the weapon's own attack animation. */
    val attackAnim: Int get() = struct()?.getIntValue(CombatIds.ABILITY_ATTACK_ANIM, NO_GRAPHIC) ?: NO_GRAPHIC

    /** Spotanim thrown at the target, and the one that plays on it when the ability connects. */
    val attackSpotanim: Int get() = struct()?.getIntValue(CombatIds.ABILITY_ATTACK_SPOTANIM, NO_GRAPHIC) ?: NO_GRAPHIC
    val impactSpotanim: Int get() = struct()?.getIntValue(CombatIds.ABILITY_IMPACT_SPOTANIM, NO_GRAPHIC) ?: NO_GRAPHIC

    /** 1 = enemy target, 2 = area, 3 = self/summon (conjures share adrenaline tiers with real thresholds). */
    val targetType: Int get() = param(CombatIds.ABILITY_TARGET_TYPE)
    val offensive: Boolean get() = targetType == 1 || targetType == 2
    val adrenalineGenerated: Int get() = param(CombatIds.ABILITY_ADRENALINE_GENERATED)
    val isChannelled: Boolean get() = param(CombatIds.ABILITY_IS_CHANNELLED) == 1
    val iconGraphic: Int get() = param(CombatIds.ABILITY_ICON)
    val parentStat: Int get() = param(CombatIds.ABILITY_PARENT_STAT)
    val levelReq: Int get() = param(CombatIds.ABILITY_LEVEL_REQ)
    val members: Boolean get() = param(CombatIds.ABILITY_IS_MEMBERS) == 1
    val isItemAbility: Boolean get() = param(CombatIds.ABILITY_IS_ITEM) == 1
    val canBeOverridden: Boolean get() = param(CombatIds.ABILITY_CAN_BE_OVERRIDDEN) == 1

    val cooldownEndVarc: Int get() = AbilityCooldownVarcs.endVarc(structId)

    fun cooldownTicks(ctx: CombatContext = CombatContext.current): Double = cooldown(ctx, gcdFallback = true)
    fun cooldownTicksIgnoreGCD(ctx: CombatContext = CombatContext.current): Double = cooldown(ctx, gcdFallback = false)
    fun cooldownMs(ctx: CombatContext = CombatContext.current): Long = cooldownTicks(ctx).toLong() * MS_PER_TICK
    fun cooldownMsIgnoreGCD(ctx: CombatContext = CombatContext.current): Long = cooldownTicksIgnoreGCD(ctx).toLong() * MS_PER_TICK
    fun offCd(ctx: CombatContext = CombatContext.current): Boolean = cooldownTicks(ctx) <= OFF_CD_THRESHOLD
    fun offCdIgnoreGCD(ctx: CombatContext = CombatContext.current): Boolean = cooldownTicksIgnoreGCD(ctx) <= OFF_CD_THRESHOLD

    private fun cooldown(ctx: CombatContext, gcdFallback: Boolean): Double {
        val now = ctx.clock.now()
        if (isItemAbility) {
            val end = ctx.varcs.getVar(CombatIds.ITEM_ABILITY_END_VARC)
            return if (end > now) (end - now).toDouble() / CombatIds.CYCLES_PER_TICK else 0.0
        }
        val endVarc = cooldownEndVarc
        if (endVarc == -1) return 0.0
        val end = ctx.varcs.getVar(endVarc)
        if (end > now) return (end - now).toDouble() / CombatIds.CYCLES_PER_TICK
        if (!gcdFallback) return 0.0
        val gcd = ctx.varcs.getVar(CombatIds.GLOBAL_COOLDOWN_END_VARC)
        return if (gcd > now) (gcd - now).toDouble() / CombatIds.CYCLES_PER_TICK else 0.0
    }

    override fun equals(other: Any?): Boolean = other is AbilityType && other.structId == structId
    override fun hashCode(): Int = structId

    companion object {
        const val MS_PER_TICK = 600L
        const val OFF_CD_THRESHOLD = 0.6
        const val NO_GRAPHIC = -1
    }
}
