package com.projectx.script.impl.trent.leagues

import com.projectx.script.api.getRealLevel
import com.projectx.script.api.varps
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.AchievementProgressRequirement
import world.gregs.voidps.cache.type.data.AchievementSkillRequirement
import world.gregs.voidps.gameval.Gameval

/**
 * A single league task, joined from the `league_task` database table, its achievement definition
 * (description, level gates, progress counters, prerequisite chain) and the varbit recording
 * completion.
 */
data class LeagueTask(
    val row: Int,
    val name: String,
    val achievement: Int,
    val description: String,
    val area: Int,
    val region: LeagueRegion?,
    val tier: Int,
    val category: Int,
    val completionVarbit: Int,
    val skillRequirements: List<AchievementSkillRequirement>,
    val progressRequirements: List<AchievementProgressRequirement>,
    val previousAchievements: List<Int>,
    val subAchievements: List<Int>,
    val points: Int,
) {
    /** Global tasks sit in area 0 and are never gated behind a region unlock. */
    val global: Boolean get() = area == GLOBAL_AREA

    /** Completed by finishing other tasks rather than by any action of its own. */
    val aggregate: Boolean get() = subAchievements.isNotEmpty()

    val completed: Boolean get() = varps.getVarBit(completionVarbit) > 0

    val levelsMet: Boolean get() = skillRequirements.all { requirement ->
        requirement.met { skill -> Skill.byId(skill)?.let(::getRealLevel) ?: 0 }
    }

    val chainMet: Boolean get() = previousAchievements.all { LeagueTasks.achievementDone(it) }

    /** How much of the task's counter is still outstanding; 0 when it tracks no counter. */
    val remaining: Int get() = progressRequirements.maxOfOrNull { it.remaining(varps::getVarBit) } ?: 0

    val target: Int get() = progressRequirements.maxOfOrNull { it.target } ?: 0

    val progress: Int get() = (target - remaining).coerceAtLeast(0)

    val started: Boolean get() = target > 0 && progress > 0

    fun unlocked(unlockedAreas: Set<Int>): Boolean = global || area in unlockedAreas

    fun actionable(unlockedAreas: Set<Int>): Boolean =
        !completed && !aggregate && chainMet && levelsMet && unlocked(unlockedAreas)

    fun missingLevels(): List<Pair<Skill, Int>> = skillRequirements
        .filterNot { requirement -> requirement.met { skill -> Skill.byId(skill)?.let(::getRealLevel) ?: 0 } }
        .flatMap { requirement -> requirement.skills.mapNotNull { Skill.byId(it) }.map { it to requirement.level } }

    val targets: LeagueTaskTargets by lazy { LeagueTargetResolver.resolve(this) }

    companion object {
        const val GLOBAL_AREA = 0
    }
}

data class LeagueRegion(val id: Int, val name: String, val areas: Set<Int>)

/**
 * Reads the league task set out of the cache once, then answers completion and unlock questions
 * against live player state. The active league picks which membership column applies - league N
 * is flagged in column N, so a task can belong to several leagues at once.
 */
object LeagueTasks {

    val activeLeague: Int get() = varps.getVar(ACTIVE_LEAGUE_VARP)

    val regions: List<LeagueRegion> by lazy {
        val names = Cache.enum(REGION_NAME_ENUM)
        Cache.dbRows(LOCALITY_TABLE).mapIndexedNotNull { index, row ->
            val id = index + 1
            val areaEnum = row.int(LOCALITY_AREA_ENUM_COLUMN) ?: return@mapIndexedNotNull null
            val areas = Cache.enum(areaEnum)?.values?.values?.filterIsInstance<Int>()?.toSet() ?: emptySet()
            LeagueRegion(id, names?.getString(id) ?: "Region $id", areas)
        }
    }

    private val regionByArea: Map<Int, LeagueRegion> by lazy {
        regions.flatMap { region -> region.areas.map { it to region } }.toMap()
    }

    val all: List<LeagueTask> by lazy { load() }

    private val byAchievement: Map<Int, LeagueTask> by lazy { all.associateBy { it.achievement } }

    /**
     * Whether an achievement in a task's prerequisite chain is done. Chains normally point at the
     * previous tier of the same league task, so its completion varbit answers directly; anything
     * outside the league task set has no varbit here and is treated as outstanding.
     */
    fun achievementDone(achievement: Int): Boolean = byAchievement[achievement]?.completed ?: false

    fun unlockedAreas(): Set<Int> {
        val unlocked = varps.getVarLong(LOCALITIES_UNLOCKED_VARP)
        return (0 until Long.SIZE_BITS).filter { unlocked shr it and 1L == 1L }.toSet()
    }

    fun unlockedRegions(): List<LeagueRegion> {
        val areas = unlockedAreas()
        return regions.filter { it.areas.isNotEmpty() && areas.containsAll(it.areas) }
    }

    private fun load(): List<LeagueTask> {
        val membership = activeLeague
        if (membership <= 0) return emptyList()
        return Cache.dbRows(TASK_TABLE).mapNotNull { row ->
            if (row.int(membership) != 1) return@mapNotNull null
            val achievementId = row.int(ACHIEVEMENT_COLUMN) ?: return@mapNotNull null
            val achievement = Cache.achievement(achievementId)
            val area = row.int(AREA_COLUMN) ?: LeagueTask.GLOBAL_AREA
            LeagueTask(
                row = row.id,
                name = Gameval.name(Gameval.DBROW, row.id) ?: "",
                achievement = achievementId,
                description = achievement?.description?.takeIf { it != "null" } ?: achievement?.name.orEmpty(),
                area = area,
                region = regionByArea[area],
                tier = row.int(TIER_COLUMN) ?: 0,
                category = row.int(CATEGORY_COLUMN) ?: 0,
                completionVarbit = (row.int(COMPLETION_COLUMN) ?: 0) and VARBIT_ID_MASK,
                skillRequirements = achievement?.skillRequirements.orEmpty(),
                progressRequirements = achievement?.progressRequirements.orEmpty(),
                previousAchievements = achievement?.previousAchievements.orEmpty(),
                subAchievements = achievement?.subAchievements.orEmpty(),
                points = achievement?.points ?: 0,
            )
        }
    }

    private const val TASK_TABLE = 334
    private const val LOCALITY_TABLE = 382
    private const val LOCALITY_AREA_ENUM_COLUMN = 1
    private const val REGION_NAME_ENUM = 9057

    private const val ACHIEVEMENT_COLUMN = 3
    private const val COMPLETION_COLUMN = 4
    private const val CATEGORY_COLUMN = 5
    private const val AREA_COLUMN = 6
    private const val TIER_COLUMN = 7

    private const val ACTIVE_LEAGUE_VARP = 12314
    private const val LOCALITIES_UNLOCKED_VARP = 12327

    /** The completion column packs a domain tag above the varbit id. */
    private const val VARBIT_ID_MASK = 0xFFFFFF
}
