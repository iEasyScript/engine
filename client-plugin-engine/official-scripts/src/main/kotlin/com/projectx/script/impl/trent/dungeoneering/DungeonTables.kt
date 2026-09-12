package com.projectx.script.impl.trent.dungeoneering

import org.projectx.core.game.skill.Skill

data class ResourceInfo(val skill: Skill, val level: Int)

data class SkillDoorInfo(val skill: Skill, val lockedAction: String)

object DungeonTables {

    private val TREES = mapOf(
        "Tangle gum tree" to 1,
        "Seeping elm tree" to 10,
        "Blood spindle tree" to 20,
        "Utuku tree" to 30,
        "Spinebeam tree" to 40,
        "Bovistrangler tree" to 50,
        "Thigat tree" to 60,
        "Corpsethorn tree" to 70,
        "Entgallow tree" to 80,
        "Grave creeper tree" to 90
    )

    private val ORES = mapOf(
        "Novite ore" to 1,
        "Bathus ore" to 10,
        "Marmaros ore" to 20,
        "Kratonite ore" to 30,
        "Fractite ore" to 40,
        "Zephyrium ore" to 50,
        "Argonite ore" to 60,
        "Katagon ore" to 70,
        "Gorgonite ore" to 80,
        "Promethium ore" to 90
    )

    private val FISHING_SPOTS = mapOf(
        "Heim crabs" to 1,
        "Red-eye" to 10,
        "Dusk eels" to 20,
        "Giant flatfish" to 30,
        "Short-finned eels" to 40,
        "Web snippers" to 50,
        "Bouldabass" to 60,
        "Salve eels" to 70,
        "Blue crabs" to 80,
        "Cave morays" to 90
    )

    private val FARMING_SPOTS = mapOf(
        "Salve nettles" to 1,
        "Wildercress" to 10,
        "Blightleaf" to 20,
        "Roseblood" to 30,
        "Bryll" to 40,
        "Duskweed" to 50,
        "Soulbell" to 60,
        "Ectograss" to 70,
        "Runeleaf" to 80,
        "Spiritbloom" to 90
    )

    val SLAYER_NPCS = mapOf(
        "Icefiend" to 1,
        "Crawling hand" to 5,
        "Cave crawler" to 10,
        "Cave slime" to 17,
        "Pyrefiend" to 30,
        "Night spider" to 41,
        "Jelly" to 52,
        "Spiritual guardian" to 63,
        "Seeker" to 71,
        "Nechryael" to 80,
        "Edimmu" to 90,
        "Soulgazer" to 99
    )

    val SKILL_DOORS = mapOf(
        "Barred door" to SkillDoorInfo(Skill.STRENGTH, "Force-bar"),
        "Runed door" to SkillDoorInfo(Skill.RUNECRAFTING, "Imbue-energy"),
        "Collapsing doorframe" to SkillDoorInfo(Skill.CONSTRUCTION, "Repair"),
        "Locked door" to SkillDoorInfo(Skill.AGILITY, "Disarm"),
        "Padlocked door" to SkillDoorInfo(Skill.THIEVING, "Pick-lock"),
        "Broken pulley door" to SkillDoorInfo(Skill.CRAFTING, "Fix-pulley"),
        "Pile of rocks" to SkillDoorInfo(Skill.MINING, "Mine"),
        "Broken key door" to SkillDoorInfo(Skill.SMITHING, "Repair-key"),
        "Flammable debris" to SkillDoorInfo(Skill.FIREMAKING, "Burn"),
        "Wooden barricade" to SkillDoorInfo(Skill.WOODCUTTING, "Chop-down"),
        "Vine-covered door" to SkillDoorInfo(Skill.FARMING, "Prune-vines"),
        "Ramokee exile" to SkillDoorInfo(Skill.SUMMONING, "Dismiss"),
        "Magical barrier" to SkillDoorInfo(Skill.MAGIC, "Dispel"),
        "Dark spirit" to SkillDoorInfo(Skill.PRAYER, "Exorcise"),
        "Liquid lock door" to SkillDoorInfo(Skill.HERBLORE, "Add-compound"),
        "Divine door" to SkillDoorInfo(Skill.DIVINATION, "Drain"),
        "Augmented door" to SkillDoorInfo(Skill.INVENTION, "Disassemble"),
        "Mound of dirt" to SkillDoorInfo(Skill.ARCHAEOLOGY, "Excavate")
    )

    private const val KEY_SHAPES = "triangle|diamond|rectangle|pentagon|corner|crescent|wedge|shield"
    val KEY_DOOR_NAME = Regex("^[A-Z][a-z]+ (?:$KEY_SHAPES) door$")
    val KEY_ITEM_NAME = Regex("^[A-Z][a-z]+ (?:$KEY_SHAPES) key$")

    // Combat-gated room exit (rand_guardian_sphere_*); opens once every monster in the room is dead.
    const val GUARDIAN_DOOR_NAME = "Guardian door"

    val DOOR_REQUIREMENT_MESSAGE =
        Regex("requires? level (\\d+) ([A-Za-z ]+?) to optimally unlock", RegexOption.IGNORE_CASE)

    private val SKILL_TIER_LEVELS: Map<Skill, List<Int>> = mapOf(
        Skill.WOODCUTTING to TREES.values.sorted(),
        Skill.MINING to ORES.values.sorted(),
        Skill.FISHING to FISHING_SPOTS.values.sorted(),
        Skill.FARMING to FARMING_SPOTS.values.sorted(),
        Skill.SLAYER to SLAYER_NPCS.values.sorted()
    )

    fun resource(locName: String): ResourceInfo? =
        TREES[locName]?.let { ResourceInfo(Skill.WOODCUTTING, it) }
            ?: ORES[locName]?.let { ResourceInfo(Skill.MINING, it) }
            ?: FISHING_SPOTS[locName]?.let { ResourceInfo(Skill.FISHING, it) }
            ?: FARMING_SPOTS[locName]?.let { ResourceInfo(Skill.FARMING, it) }

    fun slayerLevel(npcName: String): Int? = SLAYER_NPCS[npcName]

    // Once you can gather the very top tier, only the best two gatherable tiers are worth a detour —
    // everything lower you have outlevelled. Below that, the dungeon's higher tiers are simply out of
    // reach, so anything at or below your level is relevant and only above-level counts as off-path.
    fun isCriticalTier(skill: Skill, tierLevel: Int, entryLevel: Int): Boolean {
        val tiers = SKILL_TIER_LEVELS[skill] ?: return tierLevel <= entryLevel
        val highestTier = tiers.maxOrNull() ?: return tierLevel <= entryLevel
        if (entryLevel < highestTier) return tierLevel <= entryLevel
        return tierLevel in tiers.filter { it <= entryLevel }.takeLast(2)
    }

    fun skillDoor(locName: String): SkillDoorInfo? = SKILL_DOORS[locName]

    fun keyNameForDoor(doorName: String): String = doorName.removeSuffix(" door") + " key"

    fun skillByName(name: String): Skill? {
        val normalized = name.trim().uppercase().replace(" ", "")
        return when (normalized) {
            "DEFENCE" -> Skill.DEFENSE
            "HITPOINTS" -> Skill.CONSTITUTION
            "RANGE" -> Skill.RANGED
            "RUNECRAFT" -> Skill.RUNECRAFTING
            else -> Skill.entries.firstOrNull { it.name == normalized }
        }
    }
}
