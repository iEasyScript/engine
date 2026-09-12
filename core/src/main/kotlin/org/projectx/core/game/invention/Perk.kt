package org.projectx.core.game.invention

import org.projectx.core.game.Obj

enum class Perk(val id: Int, val maxRank: Int) {
    BLUNTED(1, 5),
    INACCURATE(2, 5),
    BITING(3, 4),
    ERUPTIVE(4, 4),
    HONED_TOOL(5, 6),
    LUCKY_ARMOUR(6, 6),
    UNDEAD_SLAYER(7, 1),
    DRAGON_SLAYER(8, 1),
    DEMON_SLAYER(9, 1),
    UNDEAD_BAIT(10, 1),
    DRAGON_BAIT(11, 1),
    DEMON_BAIT(12, 1),
    LOOTING(13, 1),
    ENLIGHTENED_XP(14, 4),
    GLOWWORM(15, 1),
    ATHEIST(16, 1),
    HOARDING(17, 1),
    TAUNTING(18, 1),
    COMMITTED(19, 1),
    MOBILE(20, 1),
    CAUTIOUS(21, 1),
    HALLUCINOGENIC(22, 1),
    TALKING(23, 1),
    SCAVENGING(24, 4),
    TURTLING(25, 4),
    BRIEF_RESPITE(26, 4),
    WISE(27, 4),
    EFFICIENT(28, 4),
    ABSORBATIVE(29, 4),
    PRECISE(30, 6),
    PROFANE(31, 1),
    BRASSICAN(32, 1),
    FATIGUING(33, 3),
    GENOCIDAL(34, 1),
    CRACKLING(35, 4),
    IMPATIENT(36, 4),
    INVIGORATING(37, 4),
    VENOMBLOOD(38, 1),
    DEVOTED(39, 4),
    SPENDTHRIFT(40, 6),
    SHIELD_BASHING(41, 4),
    ULTIMATUMS(42, 4),
    JUNK_FOOD(43, 3),
    ENERGISING(44, 4),
    TROPHY_TAKERS(45, 6),
    CLEAR_HEADED(46, 4),
    REFLEXES(47, 1),
    BULWARK(48, 4),
    PREPARATION(49, 4),
    MEDIOCRITY(50, 3),
    MYSTERIOUS(51, 6),
    FURNACE(52, 4),
    POLISHING(53, 4),
    CHEAPSKATE(54, 3),
    IMP_SOULED(55, 6),
    BUTTERFINGERS(56, 5),
    REFINED(57, 4),
    CHARITABLE(58, 4),
    CONFUSED(59, 3),
    CAROMING(60, 4),
    AFTERSHOCK(61, 4),
    LUNGING(62, 4),
    PLANTED_FEET(63, 1),
    ENHANCED_EFFICIENCY(64, 4),
    FLANKING(65, 4),
    ENHANCED_DEVOTION(66, 4),
    CRYSTAL_SHIELD(67, 4),
    RAPID(68, 4),
    TINKER(69, 4),
    PYROMANIAC(70, 6),
    AUTO_DISASSEMBLE(71, 6),
    PROSPER(72, 1),
    RELENTLESS(73, 5),
    RUTHLESS(74, 3),
    FORTUNE(75, 3),
    EQUILIBRIUM(76, 4),
    SCRAPS(77, 1),
    CARELESS(78, 5),
    EXPLOSIVE(79, 1),
    OBLIVIOUS(80, 1),
    WILD_RUNES(81, 5),
    PRESERVATIONIST(82, 5),
    HASTY(83, 5),
    NATURALIST(84, 5),
    ;

    fun rank(rank: Int): PerkRank = PerkRank(this, rank.coerceIn(1, maxRank))
    val maxed: PerkRank get() = PerkRank(this, maxRank)
}

sealed interface GizmoSpec

data class PerkRank(val perk: Perk, val rank: Int) : GizmoSpec {
    operator fun plus(other: PerkRank): Gizmo = Gizmo(this, other)
}

data class Gizmo(val perk1: PerkRank, val perk2: PerkRank? = null) : GizmoSpec

private fun GizmoSpec.perks(): Pair<PerkRank, PerkRank?> = when (this) {
    is PerkRank -> this to null
    is Gizmo -> perk1 to perk2
}

fun Obj.augmented(vararg gizmos: GizmoSpec, itemLevel: Int = Invention.MAX_ITEM_LEVEL): Obj =
    augmented(gizmos.asList(), itemLevel)

fun Obj.augmented(gizmos: List<GizmoSpec>, itemLevel: Int = Invention.MAX_ITEM_LEVEL): Obj {
    var obj = withVar("invent_obj_xp", Invention.xpForLevel(itemLevel))
    for ((index, spec) in gizmos.withIndex()) {
        val g = index + 1
        val (perk1, perk2) = spec.perks()
        obj = obj.withVar("invent_gizmo_${g}_perk_1", perk1.perk.id)
            .withVar("invent_gizmo_${g}_perk_1_rank", perk1.rank)
        if (perk2 != null) {
            obj = obj.withVar("invent_gizmo_${g}_perk_2", perk2.perk.id)
                .withVar("invent_gizmo_${g}_perk_2_rank", perk2.rank)
        }
    }
    return obj
}

val Obj.itemXp: Int get() = attributes?.getInt("invent_obj_xp") ?: 0
val Obj.itemLevel: Int get() = Invention.levelForXp(itemXp)
