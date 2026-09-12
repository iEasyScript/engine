package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

/** A level gate; each entry of [skills] indexes `org.projectx.core.game.skill.Skill`. */
data class AchievementSkillRequirement(
    val group: Int = 0,
    val skills: List<Int> = emptyList(),
    val level: Int = 0,
    val text: String = "",
) {
    fun met(levelOf: (skill: Int) -> Int): Boolean = skills.all { levelOf(it) >= level }
}

/**
 * A counter the achievement waits on: [target] of whatever [vars] track. Reading the vars gives
 * progress so far, which is what separates a task that is nearly done from one not started.
 */
data class AchievementProgressRequirement(
    val group: Int = 0,
    val vars: List<Int> = emptyList(),
    val target: Int = 0,
    val text: String = "",
) {
    fun remaining(valueOf: (varId: Int) -> Int): Int =
        (target - (vars.minOfOrNull(valueOf) ?: 0)).coerceAtLeast(0)
}

/** A reference to another definition of the same kind - another achievement, or a quest. */
data class AchievementLink(val group: Int = 0, val id: Int = 0)

/** A var the achievement watches for a single [value] rather than for a rising count. */
data class AchievementVarValue(
    val group: Int = 0,
    val varId: Int = 0,
    val amount: Int = 0,
    val text: String = "",
    val value: Int = 0,
)

/**
 * Requirements come in pairs of the same shape - the plain list and the `sub` list - and every record
 * names the requirement group it belongs to. [requirementGroupTargets] says how many of each group's
 * plain records have to be met, [subRequirementGroupTargets] the same over the `sub` records, and the
 * two `Groups` counts how many of those groups the achievement needs.
 */
data class AchievementType(
    override var id: Int = -1,
    var name: String = "null",
    var texts: MutableMap<Int, String> = mutableMapOf(),
    var category: Int = -1,
    var graphicId: Int = -1,
    var points: Int? = null,
    var unknown6: Int = -1,
    var reward: String = "null",
    var levelRequirements: List<AchievementSkillRequirement>? = null,
    var varpRequirements: List<AchievementProgressRequirement>? = null,
    var varbitRequirements: List<AchievementProgressRequirement>? = null,
    var previousAchievementLinks: List<AchievementLink>? = null,
    var levelSubRequirements: List<AchievementSkillRequirement>? = null,
    var varpSubRequirements: List<AchievementProgressRequirement>? = null,
    var varbitSubRequirements: List<AchievementProgressRequirement>? = null,
    var subAchievementLinks: List<AchievementLink>? = null,
    var subcategory: Int = -1,
    var hidden: Boolean = false,
    var unknown18: Int = -1,
    var unknown19: Boolean = true,
    var questRequirements: List<AchievementLink>? = null,
    var questSubRequirements: List<AchievementLink>? = null,
    var varpValueRequirements: List<AchievementVarValue>? = null,
    var varpValueSubRequirements: List<AchievementVarValue>? = null,
    var varbitValueRequirements: List<AchievementVarValue>? = null,
    var varbitValueSubRequirements: List<AchievementVarValue>? = null,
    var unknown26: Int = -1,
    var unknown27: Boolean = false,
    var requirementGroupTargets: List<Int>? = null,
    var requirementGroups: Int = -1,
    var subRequirementGroupTargets: List<Int>? = null,
    var subRequirementGroups: Int = -1,
    var unknown32a: Int = -1,
    var unknown32b: Int = -1,
    var unknown32c: Int = -1,
) : CacheType, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    val description: String get() = texts[0] ?: "null"

    val skillRequirements: List<AchievementSkillRequirement>
        get() = levelRequirements.orEmpty() + levelSubRequirements.orEmpty()

    val progressRequirements: List<AchievementProgressRequirement>
        get() = varpRequirements.orEmpty() + varbitRequirements.orEmpty() +
            varpSubRequirements.orEmpty() + varbitSubRequirements.orEmpty()

    val previousAchievements: List<Int> get() = previousAchievementLinks.orEmpty().map { it.id }

    val subAchievements: List<Int> get() = subAchievementLinks.orEmpty().map { it.id }

    /** Aggregates completed by finishing their [subAchievements] rather than by any action of their own. */
    val aggregate: Boolean get() = subAchievementLinks?.isNotEmpty() == true
}
