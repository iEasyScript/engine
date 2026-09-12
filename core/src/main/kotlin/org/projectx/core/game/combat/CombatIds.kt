package org.projectx.core.game.combat

import world.gregs.voidps.gameval.Gameval
import world.gregs.voidps.gameval.Gameval.ENUM
import world.gregs.voidps.gameval.Gameval.PARAM
import world.gregs.voidps.gameval.Gameval.STRUCT
import world.gregs.voidps.gameval.Gameval.VARBIT
import world.gregs.voidps.gameval.Gameval.VAR_CLIENT
import world.gregs.voidps.gameval.Gameval.VAR_PLAYER

object CombatIds {
    private fun param(name: String) = Gameval.requireId(PARAM, name)
    private fun enumId(name: String) = Gameval.requireId(ENUM, name)
    private fun struct(name: String) = Gameval.requireId(STRUCT, name)
    private fun varbit(name: String) = Gameval.requireId(VARBIT, name)
    private fun varc(name: String) = Gameval.requireId(VAR_CLIENT, name)
    private fun varp(name: String) = Gameval.requireId(VAR_PLAYER, name)

    const val CYCLES_PER_TICK = 30

    val ABILITY_ID = param("combatv2_ability_id")
    val ABILITY_NAME = param("combatv2_ability_name")
    val ABILITY_INFO = param("combatv2_ability_info")
    val ABILITY_COOLDOWN = param("combatv2_ability_cooldown")
    val ABILITY_DURATION = param("combatv2_ability_duration")
    val ABILITY_ADRENALINE_REQ = param("combatv2_ability_adrenaline_req")
    val ABILITY_ADRENALINE_TYPE = param("combatv2_ability_adrenaline_type")
    val ABILITY_ADRENALINE_GENERATED = param("combatv2_ability_adrenaline_generated")
    val ABILITY_IS_CHANNELLED = param("combatv2_ability_is_channelled")
    val ABILITY_ICON = param("combatv2_ability_icon")
    val ABILITY_PARENT_STAT = param("combatv2_ability_parent_stat")
    val ABILITY_LEVEL_REQ = param("combatv2_ability_level_req")
    val ABILITY_IS_MEMBERS = param("combatv2_ability_is_members")
    val ABILITY_IS_ITEM = param("combatv2_is_item_ability")
    val ABILITY_CAN_BE_OVERRIDDEN = param("combatv2_ability_can_be_overridden")
    val ABILITY_STUNS = param("combatv2_ability_stuns")
    val ABILITY_ATTACK_ANIM = param("combatv2_attack_anim")
    val ABILITY_ATTACK_SPOTANIM = param("combatv2_attack_spotanim")
    val ABILITY_IMPACT_SPOTANIM = param("combatv2_impact_spotanim")
    val ABILITY_TARGET_TYPE = param("combatv2_ability_target_type")

    val BUFF_STRUCT = param("combatv2_buff_struct")
    val BUFF_TYPE = param("combatv2_buff_type")
    val BUFF_USES_STACKS = param("combatv2_buff_uses_stacks")
    val BUFF_USES_STACKS_PERCENT = param("combatv2_buff_uses_stacks_percent")
    val BUFF_USES_ABILITY_END = param("combatv2_buff_uses_ability_end")
    val BUFF_CATEGORY = param("combatv2_buff_category")
    val BUFF_PRIORITY = param("combatv2_buff_priority")
    val BUFF_VAR = param("combatv2_buff_var")
    val BUFF_TOGGLE_VAR = param("combatv2_buff_toggle_var")

    val ENUM_MELEE = enumId("combatv2_abilities_melee")
    val ENUM_DEFENCE = enumId("combatv2_abilities_defence")
    val ENUM_CONSTITUTION = enumId("combatv2_abilities_constitution")
    val ENUM_RANGED = enumId("combatv2_abilities_ranged")
    val ENUM_PRAYER = enumId("combatv2_abilities_prayer")
    val ENUM_MAGIC = enumId("combatv2_abilities_magic")
    val ENUM_NECROMANCY = enumId("combatv2_abilities_necromancy")
    val ENUM_ANCIENT = enumId("thas_index_to_struct")
    val ENUM_SUMMONING = enumId("combatv2_summoning_button_id_to_struct")
    val ENUM_EMOTES = enumId("emotes2_structs")
    val ENUM_ALL_BUFFS = enumId("combatv2_buff_all_buffs")
    val ENUM_ALL_BUFFS_AND_DEBUFFS = enumId("combatv2_buff_all_buffs_and_debuffs")

    val STRUCT_SPECIAL_ATTACK = struct("combatv2_run_button_dummy_struct")
    val STRUCT_HP_BUTTON = struct("combatv2_hp_button_dummy_struct")
    val STRUCT_WORN_SLOT = struct("combatv2_worn_slot_dummy_struct")
    val STRUCT_AUTO_RETALIATE = struct("combatv2_auto_retaliate_button_dummy_struct")
    val STRUCT_OVERHEAD_EMOTE = struct("combatv2_overhead_emote_dummy_struct")

    /** Dummy abilities whose only job is to carry the shared cooldown length every ability waits on. */
    val STRUCT_GLOBAL_COOLDOWN = struct("combatv2_dummy_global_cooldown_ability")
    val STRUCT_DOUBLE_GLOBAL_COOLDOWN = struct("combatv2_dummy_double_global_cooldown_ability")

    val CURRENT_BAR = varbit("combatv2_actionbar_current_bar")
    val ADDITIONAL_BARS = intArrayOf(
        varbit("combatv2_actionbar_current_bar2"),
        varbit("combatv2_actionbar_current_bar3"),
        varbit("combatv2_actionbar_current_bar4"),
        varbit("combatv2_actionbar_current_bar5"),
    )
    val MOBILE_BARS = intArrayOf(
        varbit("combatv2_actionbar_current_bar_mobile"),
        varbit("combatv2_actionbar_current_bar_mobile_revo"),
    )

    val GLOBAL_COOLDOWN_END_VARC = varc("combatv2_global_cooldown_end_client")
    val ITEM_ABILITY_END_VARC = varc("combatv2_cooldown_item_ability_1_end_client")

    val QUEUING_OFF = varbit("combatv2_queuing_off")
    val QUEUED_SLOT = varp("combatv2_queued_slot")
    val QUEUED_BAR = varp("combatv2_queued_bar")

    val CONJURE_SKELETON = struct("combatv2_ability_necromancy_conjure_skeleton_warrior")
    val COMMAND_SKELETON = struct("combatv2_ability_necromancy_command_skeleton_warrior")
    val CONJURE_PUTRID = struct("combatv2_ability_necromancy_conjure_putrid_zombie")
    val COMMAND_PUTRID = struct("combatv2_ability_necromancy_command_putrid_zombie")
    val CONJURE_VENGEFUL = struct("combatv2_ability_necromancy_conjure_vengeful_ghost")
    val COMMAND_VENGEFUL = struct("combatv2_ability_necromancy_command_vengeful_ghost")
    val CONJURE_PHANTOM = struct("combatv2_ability_necromancy_conjure_phantom_guardian")
    val COMMAND_PHANTOM = struct("combatv2_ability_necromancy_command_phantom_guardian")
    val SPECTRAL_SCYTHE = struct("combatv2_ability_necromancy_spectral_scythe")
    val SPECTRAL_SCYTHE_RECAST_1 = struct("combatv2_ability_necromancy_spectral_scythe_recast_1")
    val SPECTRAL_SCYTHE_RECAST_2 = struct("combatv2_ability_necromancy_spectral_scythe_recast_2")

    val CONJURE_SKELETON_ACTIVE = varp("combatv2_ability_necromancy_conjure_skeleton_warrior_active")
    val CONJURE_PUTRID_ACTIVE = varp("combatv2_ability_necromancy_conjure_putrid_zombie_active")
    val CONJURE_VENGEFUL_ACTIVE = varp("combatv2_ability_necromancy_conjure_vengeful_ghost_active")
    val CONJURE_PHANTOM_ACTIVE = varp("combatv2_ability_necromancy_conjure_phantom_guardian_active")
    val COMMAND_SKELETON_UNLOCKED = varbit("combatv2_ability_necromancy_command_skeleton_warrior_unlocked")
    val COMMAND_PUTRID_UNLOCKED = varbit("combatv2_ability_necromancy_command_putrid_zombie_unlocked")
    val COMMAND_VENGEFUL_UNLOCKED = varbit("combatv2_ability_necromancy_command_vengeful_ghost_unlocked")
    val COMMAND_PHANTOM_UNLOCKED = varbit("combatv2_ability_necromancy_command_phantom_guardian_unlocked")
    val SPECTRAL_SCYTHE_RECAST_1_ACTIVE = varp("combatv2_ability_necromancy_spectral_scythe_recast_1_active")
    val SPECTRAL_SCYTHE_RECAST_2_ACTIVE = varp("combatv2_ability_necromancy_spectral_scythe_recast_2_active")
}
