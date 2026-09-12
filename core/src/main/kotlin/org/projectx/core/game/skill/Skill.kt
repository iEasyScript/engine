package org.projectx.core.game.skill

enum class Skill {
    ATTACK,
    DEFENSE,
    STRENGTH,
    CONSTITUTION,
    RANGED,
    PRAYER,
    MAGIC,
    COOKING,
    WOODCUTTING,
    FLETCHING,
    FISHING,
    FIREMAKING,
    CRAFTING,
    SMITHING,
    MINING,
    HERBLORE,
    AGILITY,
    THIEVING,
    SLAYER,
    FARMING,
    RUNECRAFTING,
    HUNTER,
    CONSTRUCTION,
    SUMMONING,
    DUNGEONEERING,
    DIVINATION,
    INVENTION,
    ARCHAEOLOGY,
    NECROMANCY;

    companion object {
        val ALL: List<Skill> = entries
        fun byId(id: Int): Skill? = entries.getOrNull(id)
    }
}
