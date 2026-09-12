package org.projectx.core.game.combat

enum class Ability(val structId: Int) {
    ANTICIPATION(14710),
    BARRICADE(14719),
    BLADED_DIVE(1488),
    BLOAT(48308),
    COMMAND_SKELETON_WARRIOR(48303),
    COMMAND_VENGEFUL_GHOST(48307),
    CONJURE_UNDEAD_ARMY(33965),
    DARKNESS(48331),
    DEATH_SKULLS(48314),
    DEBILITATE(14717),
    DEVOTION(25028),
    DIVE(47129),
    EAT_FOOD(44225),
    EDGEVILLE_LODESTONE(24225),
    ESCAPE(14665),
    FINGER_OF_DEATH(48297),
    FREEDOM(14711),
    INVOKE_DEATH(48330),
    LESSER_BONE_SHIELD(48326),
    LIVING_DEATH(48324),
    MAGIC_BASIC_ATTACK(52777),
    MELEE_BASIC_ATTACK(49531),
    NECRO_BASIC_ATTACK(48293),
    PROVOKE(14712),
    RANGED_BASIC_ATTACK(52795),
    REFLECT(14716),
    RESONANCE(14713),
    SOUL_SAP(48298),
    SOUL_STRIKE(48299),
    SPECTRAL_SCYTHE(48311),
    SPLIT_SOUL(48332),
    SURGE(14726),
    THREADS_OF_FATE(48329),
    TOUCH_OF_DEATH(48296),
    VOLLEY_OF_SOULS(48301),
    WARS_RETREAT_TELEPORT(11659),
    WEAPON_SPECIAL_ATTACK(28430);

    val type: AbilityType get() = AbilityRegistry[structId] ?: AbilityType(structId)
    val offCd: Boolean get() = type.offCd()
    val offCdIgnoreGCD: Boolean get() = type.offCdIgnoreGCD()
    val cooldownTicks: Double get() = type.cooldownTicks()
    val cooldownTicksIgnoreGCD: Double get() = type.cooldownTicksIgnoreGCD()
    val cooldownMs: Long get() = type.cooldownMs()

    companion object {
        val byStructId: Map<Int, Ability> = entries.associateBy { it.structId }
    }
}
