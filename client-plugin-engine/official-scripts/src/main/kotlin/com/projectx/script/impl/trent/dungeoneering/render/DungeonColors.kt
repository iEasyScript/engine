package com.projectx.script.impl.trent.dungeoneering.render

import org.projectx.core.game.skill.Skill
import com.projectx.ui.backend.dsl.utils.ImGuiColors

object DungeonColors {
    val WOODCUTTING = ImGuiColors.hex("#4CAF50")
    val MINING = ImGuiColors.hex("#64B5F6")
    val FISHING = ImGuiColors.hex("#4DD0E1")
    val FARMING = ImGuiColors.hex("#9CCC65")
    val SLAYER = ImGuiColors.hex("#FF7043")
    val UNATTAINABLE = ImGuiColors.hex("#FF3030")
    val ATTAINABLE = ImGuiColors.hex("#40FF60")
    val OFF_TIER = ImGuiColors.hex("#808088")
    val UNKNOWN = ImGuiColors.hex("#B0B0B0")
    val KEY = ImGuiColors.hex("#FF00FF")
    val KEY_RING = ImGuiColors.hex("#FFFF00")
    val DOOR_BOOSTABLE = ImGuiColors.hex("#FFD030")
    val DOOR_IMPOSSIBLE = ImGuiColors.hex("#FF3030")
    val BONUS_SHADE = ImGuiColors.rgba(180, 30, 30, 105)
    val RECOMMENDED = ImGuiColors.hex("#FFFFFF")

    fun forSkill(skill: Skill): Int = when (skill) {
        Skill.WOODCUTTING -> WOODCUTTING
        Skill.MINING -> MINING
        Skill.FISHING -> FISHING
        Skill.FARMING -> FARMING
        Skill.SLAYER -> SLAYER
        else -> UNKNOWN
    }
}
