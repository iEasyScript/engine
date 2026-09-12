package com.projectx.script.impl.devin.zuk

import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.varps

/**
 * Ordered most to least urgent. Only the first applicable action is ever surfaced, so the player
 * reads one instruction under pressure instead of triaging a list.
 */
enum class ZukAction(val label: String, val detail: String, val rgb: Int) {
    MOVE("MOVE", "Standing in a hazard", 0xFF3B30),
    RUN("RUN", "Searing pain drains max health - do not eat, food heals less", 0xFF6B00),
    SURVIVE("SURVIVE", "Immune - block 4 attacks", 0xFF9F0A),
    UNLEASH("IGNEOUS VENGEANCE", "Energy full - activate to stagger Zuk", 0xFF9F0A),
    BURST("BURST IT", "Kill before it explodes", 0xFF453A),
    BIG_HIT("BIG HITS", "Only hits above 3000 land", 0xFF375F),
    STUN("STUN IT", "Stun breaks the damage reduction", 0xFFD60A),
    THRESHOLD("THRESHOLD", "Adrenaline ability breaks the shield", 0x30D158),
    GET_UNDER("GET UNDER", "Stand in the dome, hit 2-3 times", 0x64D2FF),
    KILL_KIH("KILL THE KIH", "It steals health and drains adrenaline", 0xFF6482),
    UNGROUND("MAKE IT MOVE", "The Tok-Xil gains +1% damage per stack while standing still", 0xFFB340),
    VULN("VULN BOMB", "Target is not vulnerable", 0xBF5AF2);

    val isCritical: Boolean
        get() = this == MOVE || this == RUN || this == SURVIVE || this == UNLEASH
}

object ZukActions {

    private const val NEARBY = 30

    /**
     * [kihDraining] gates the Kih prompt on it actually having drained rather than merely existing:
     * a safespotted Kih is present for most of the encounter and would otherwise pin the prompt on
     * an enemy the player is correctly ignoring. [attackingWorthyTarget] gates the vuln prompt on
     * the player's own combat target, because the bomb lands on whatever is being attacked - a
     * worthy npc merely being nearby made the 17:45 run bomb trash all wave.
     */
    fun current(
        wave: WaveInfo?,
        standingInHazard: Boolean,
        kihDraining: Boolean,
        attackingWorthyTarget: Boolean
    ): ZukAction? {
        if (standingInHazard) return ZukAction.MOVE
        if (searingPainStacks() > 0) return ZukAction.RUN

        val present = allNpcsWithinRange(NEARBY) { it.exists() }.mapNotNull { it.zukMinion() }.toSet()

        if (ZukMinion.FATAL_GENERIC in present || ZukMinion.FATAL_RANGED in present || ZukMinion.FATAL_MAGIC in present) {
            return ZukAction.SURVIVE
        }
        if (igneousEnergyFull()) return ZukAction.UNLEASH
        if (ZukMinion.VOLATILE_HUR in present) return ZukAction.BURST
        if (ZukMinion.UNBREAKABLE_KET in present) return ZukAction.BIG_HIT
        if (ZukMinion.IGNEOUS_HUR in present && !targetStunned()) return ZukAction.STUN
        if (ZukMinion.IGNEOUS_XIL in present) return ZukAction.THRESHOLD
        if (ZukMinion.IGNEOUS_MEJ in present) return ZukAction.GET_UNDER
        if (kihDraining && (ZukMinion.KIH in present || ZukMinion.SLAYER_DEBUFF in present)) return ZukAction.KILL_KIH
        if (ZukMinion.TOK_XIL in present && groundedStacks() >= ZukIds.UNGROUND_AT_STACKS) return ZukAction.UNGROUND
        if (attackingWorthyTarget && !targetVulnerable()) return ZukAction.VULN
        return null
    }

    fun igneousEnergyFull(): Boolean =
        varps.getVarBit(ZukIds.IGNEOUS_ENERGY_VARBIT) >= ZukIds.IGNEOUS_ENERGY_FULL

    /** Stacks remaining; each tile moved sheds one, so this doubles as "tiles left to run". */
    fun searingPainStacks(): Int = varps.getVar(ZukIds.SEARING_PAIN_VARP).coerceAtLeast(0)

    fun inZukPhase(): Boolean = varps.getVar(ZukIds.ENCOUNTER_PROGRESS_VARP) >= ZukIds.ENCOUNTER_PROGRESS_ZUK

    /**
     * Clears the moment Zuk dies, while his npc lingers kneeling for the exit prompt - without this
     * the coach keeps calling prayers at a corpse for as long as the player stands in the arena.
     */
    fun inEncounter(): Boolean = varps.getVarBit(ZukIds.ENCOUNTER_MODE_VARBIT) != 0

    fun targetStunned(): Boolean =
        varps.getVarBit(ZukIds.TARGET_STUNNED_VARBIT) != 0 || varps.getVarBit(ZukIds.TARGET_BOUND_VARBIT) != 0

    fun targetVulnerable(): Boolean = varps.getVarBit(ZukIds.TARGET_VULNERABLE_VARBIT) != 0

    fun groundedStacks(): Int = varps.getVarBit(ZukIds.TOK_XIL_GROUNDED_STACKS_VARBIT)
}
