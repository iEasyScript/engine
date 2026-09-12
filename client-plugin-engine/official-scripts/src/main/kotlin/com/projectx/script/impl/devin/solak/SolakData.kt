package com.projectx.script.impl.devin.solak

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.varps
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.gameval.Gameval.ENUM
import world.gregs.voidps.gameval.Gameval.NPC as NPC_DOMAIN
import world.gregs.voidps.gameval.Gameval.SEQ
import world.gregs.voidps.gameval.Gameval.VARBIT

const val UNRESOLVED = -1

private fun resolve(type: String, name: String): Int =
    runCatching { Gameval.requireId(type, name) }.getOrDefault(UNRESOLVED)

object SolakIds {
    val BOSS = resolve(NPC_DOMAIN, "nature_boss_main")
    val CORE = resolve(NPC_DOMAIN, "nature_boss_main_boss_core_attack_point")
    val ROOTLING = resolve(NPC_DOMAIN, "nature_boss_volatile_root")
    val LASHER = resolve(NPC_DOMAIN, "nature_boss_blightbound_lasher")
    val MERETHIEL = resolve(NPC_DOMAIN, "nature_boss_finale_merethiel_in_battle")
    val ENERGY_ORB = resolve(NPC_DOMAIN, "nature_boss_energy_orb")
    val BLIGHT_STORM = resolve(NPC_DOMAIN, "nature_boss_blight_storm")
    val ENCOUNTER_START = resolve(NPC_DOMAIN, "nature_boss_start_encounter")

    val BLIGHT_STACKS = resolve(VARBIT, "nature_boss_player_blight_stacks")
    val NATURES_BLESSING = resolve(VARBIT, "nature_boss_natures_blessing")
    val EXTRA_ACTION_ACTIVE = resolve(VARBIT, "nature_boss_extra_action_button_active")
    val HEALTHBAR_STATE = resolve(VARBIT, "nature_boss_healthbar_state")
    val SECOND_BAR_LP = resolve(VARBIT, "nature_boss_second_health_bar_boss_player")
    val TEAM_SIZE = resolve(VARBIT, "nature_boss_start_player_count_player")
    val PRAYER_BOOK_ACTIVE = resolve(VARBIT, "nature_boss_prayer_restore_book_active")
    val PRAYER_BOOK_TIME = resolve(VARBIT, "nature_boss_prayer_restore_book_time")

    val CORE_LP_BY_TEAM = resolve(ENUM, "nature_boss_scaling_core_lifepoint_values")
    val PHASE_3_LP_BY_TEAM = resolve(ENUM, "nature_boss_phase_3_lifepoint_values")
    val ARMS_LP_BY_TEAM = resolve(ENUM, "nature_boss_arms_lifepoint_values")
    val LEGS_LP_BY_TEAM = resolve(ENUM, "nature_boss_legs_lifepoint_values")
}

fun readVarbit(id: Int): Int? =
    if (id == UNRESOLVED) null else runCatching { varps.getVarBit(id) }.getOrNull()

fun scaledLifepoints(enumId: Int, teamSize: Int): Int {
    if (enumId == UNRESOLVED || teamSize <= 0) return 0
    return runCatching { Cache.enum(enumId)?.getInt(teamSize) ?: 0 }.getOrDefault(0)
}

private fun NPC.typeKey(): Int = if (typeId != -1) typeId else id

fun NPC.isType(type: Int): Boolean = type != UNRESOLVED && typeKey() == type

fun NPC.anchorsEncounter(): Boolean = isType(SolakIds.BOSS) || isType(SolakIds.ENCOUNTER_START)

enum class DpsWindow(val label: String) {
    ARMS("ARMS"),
    LEGS("LEGS")
}

enum class SolakLimb(gameval: String, private val fallbackLabel: String, val window: DpsWindow) {
    LEFT_ARM("nature_boss_main_boss_left_hand", "Solak's left arm", DpsWindow.ARMS),
    RIGHT_ARM("nature_boss_main_boss_right_hand", "Solak's right arm", DpsWindow.ARMS),

    // The leg gamevals are swapped against what the client renders: the one named right displays
    // as "Solak's left leg". Keyed on the id; the label only ever comes from the npc definition.
    LEFT_LEG("nature_boss_main_boss_right_leg", "Solak's left leg", DpsWindow.LEGS),
    RIGHT_LEG("nature_boss_main_boss_left_leg", "Solak's right leg", DpsWindow.LEGS);

    val id: Int = resolve(NPC_DOMAIN, gameval)

    val label: String
        get() = runCatching { Cache.npc(id)?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" } ?: fallbackLabel

    val maxLifepointsEnum: Int
        get() = if (window == DpsWindow.ARMS) SolakIds.ARMS_LP_BY_TEAM else SolakIds.LEGS_LP_BY_TEAM

    companion object {
        val resolved: List<SolakLimb> = entries.filter { it.id != UNRESOLVED }
    }
}

enum class Severity { CRITICAL, WARNING, INFO }

enum class SolakTelegraph(
    gameval: String,
    val instruction: String,
    val severity: Severity,
    val opensWindow: DpsWindow? = null,
    val closesWindow: Boolean = false
) {
    BINDING_CRUSH("nature_boss_solak_attack_binding_crush", "BINDING CRUSH - ESCAPE OR DIE", Severity.CRITICAL),
    BINDING_CRUSH_LOOP("nature_boss_solak_attack_binding_crush_loop", "BINDING CRUSH - ESCAPE OR DIE", Severity.CRITICAL),
    BINDING_CRUSH_RECOVERY("nature_boss_solak_attack_binding_crush_recovery", "ARM STUCK - FREE DPS", Severity.INFO),

    EARTHEN_SEED("nature_boss_solak_special_attack_earthen_seed", "EARTHEN SEED - KILL THE BOMBS BEFORE THEY LAND", Severity.CRITICAL),
    EARTHEN_SEED_RUPTURE("nature_boss_earthen_seed_rupture_telegraphing", "EARTHEN SEED RUPTURING", Severity.CRITICAL),
    EARTHEN_SEED_MARK("nature_boss_solak_earthen_seed_ground_mark", "EARTHEN SEED - GROUND MARKED", Severity.WARNING),

    ROOT_SPIRE("nature_boss_solak_attack_root_spire", "ROOTING CLAW - ROOTS ERUPTING", Severity.WARNING),

    RIGHT_HAND_SMASH("nature_boss_solak_attack_right_hand_smash", "HAND SMASH", Severity.INFO),
    LEFT_ARM_SWING("nature_boss_solak_attack_left_arm_swing", "ARM SWING", Severity.INFO),
    BOTH_HANDS_SMASH("nature_boss_solak_attack_both_hands_smash", "BOTH HANDS SMASH", Severity.INFO),
    STAMP("nature_boss_solak_attack_stamp", "STAMP", Severity.INFO),

    STAGGERED_FOR_LEGS("nature_boss_solak_staggared_for_leg_dps", "STAGGERED - HIT THE LEGS", Severity.INFO, opensWindow = DpsWindow.LEGS),
    KNEELING_FOR_ARMS("nature_boss_solak_kneel_for_arms_dps", "KNEELING - HIT THE ARMS", Severity.INFO, opensWindow = DpsWindow.ARMS),
    RECOVERING_FROM_LEGS("nature_boss_solak_staggared_from_leg_dps", "RECOVERING", Severity.INFO, closesWindow = true),
    BACK_TO_IDLE("nature_boss_solak_staggared_back_to_idle", "RECOVERING", Severity.INFO, closesWindow = true),

    DRAWS_ENERGY("nature_boss_solak_draws_energy", "SOLAK DRAWS ENERGY", Severity.WARNING),
    SPINNING_SPECIAL("nature_boss_solak_spinning_special", "SPINNING SPECIAL", Severity.WARNING),
    POWER_FLOWS("nature_boss_solak_attack_power_flows", "POWER FLOWS", Severity.WARNING),
    NATURES_BLESSING("nature_boss_natures_blessing_telegraph", "NATURE'S BLESSING DOME", Severity.INFO);

    val id: Int = resolve(SEQ, gameval)

    companion object {
        private val byId = entries.filter { it.id != UNRESOLVED }.associateBy { it.id }
        fun of(animation: Int): SolakTelegraph? = byId[animation]
    }
}
