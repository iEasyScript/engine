package org.projectx.core.game.combat

enum class Effect(val structId: Int) {
    ADRENALINE_POTION_PREVENTION(11599),
    ADRENALINE_RENEWAL(11602),
    AGGRESSION(37207),
    AGGRESSION_POTION(33986),
    ATTACK_STAT_BOOSTED(14885),
    BARRICADE(14719),
    BEACH_HOLE_COCKTAIL(45327),
    BONE_SHIELD(48340),
    BONFIRE_BOOST(31989),
    CHARMING_POTION(36921),
    CONJURE_SKELETON_WARRIOR(48302),
    CONJURE_VENGEFUL_GHOST(48306),
    DARKNESS(48343),
    DEATH_ESSENCE_DEBUFF(48349),
    DEATH_SPARK(48350),
    DEBILITATE(14717),
    DEEP_SEA_FISHING(39440),
    DEFENCE_STAT_BOOSTED(14889),
    DEVOTION(25028),
    DREADNIP(35751),
    ENHANCED_EXCALIBUR(35748),
    FAMILIAR_SUMMONED(27609),
    GEOTHERMAL_BURN(45559),
    INVOKE_DEATH(48330),
    JUJU_FARMING_POTION(29598),
    LEMON_SOUR_BEACH_COCKTAIL(45326),
    LIVING_DEATH(48339),
    MAGIC_STAT_BOOSTED(14895),
    NECROSIS(48333),
    OVERLOADED(23129),
    POISONOUS(14901),
    POWDER_OF_BURIALS(46033),
    POWDER_OF_PENANCE(46034),
    POWERBURST_OF_LIFEFORCE(38075),
    POWERBURST_OF_SORCERY(38071),
    POWERBURST_POTION_IS_ON_COOLDOWN(920),
    PRAYER_RENEW(14905),
    PROTECTION_PRAYER_BLOCKED(37423),
    RANGED_STAT_BOOSTED(14891),
    REFLECT(14716),
    RESIDUAL_SOUL(48334),
    RESIDUAL_SOUL_OPPONENT(47807),
    RESONANCE(14713),
    RUNIC_ATTUNER(50212),
    SCARAB_STACK(46027),
    SEARING_PAIN(45560),
    SKELETON_WARRIOR(48335),
    STRENGTH_STAT_BOOSTED(14887),
    STUNNED(14883),
    SUPER_PRAYER_RENEWAL_POTION(29604),
    THREADS_OF_FATE(48341),
    VENGEFUL_GHOST(48337),
    VENGEFUL_GHOST_HAUNT(1000),
    VULNERABILITY(14784),
    WEAPON_SPECIAL_ATTACK(28430);

    val type: EffectType get() = EffectRegistry[structId] ?: EffectType(structId)
    val isDebuff: Boolean get() = type.isDebuff
    val active: Boolean get() = EffectVarTables.active(structId, CombatContext.current)
    val notActive: Boolean get() = !active
    val activeOnOpponent: Boolean get() = EffectVarTables.activeOnOpponent(structId, CombatContext.current)
    val notActiveOnOpponent: Boolean get() = !activeOnOpponent
    val stacks: Int get() = EffectVarTables.stacks(structId, CombatContext.current)
    val timeRemaining: Long get() = EffectVarTables.timeRemainingMs(structId, CombatContext.current)

    companion object {
        val byStructId: Map<Int, Effect> = entries.associateBy { it.structId }
        operator fun get(structId: Int): Effect? = byStructId[structId]
    }
}
