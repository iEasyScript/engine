package com.projectx.quest.data

data class Quest(
    val slug: String,
    val name: String,
    val members: Boolean = false,
    val length: String? = null,
    val timeline: String? = null,
    val releaseDate: Long = 0L,
    val neededItems: List<QuestItemReq> = emptyList(),
    val recommendedItems: List<QuestItemReq> = emptyList(),
    val combatNPCs: List<CombatNpcInfo> = emptyList(),
    val prereqQuests: List<String> = emptyList(),
    val questReqs: List<QuestReq> = emptyList(),
    val steps: List<QuestStep> = emptyList(),
    val stageVarbit: Int = -1,
    val stageVarbitCompleteValue: Int = -1,
    val cacheQuestId: Int = -1,
)

data class QuestStep(
    val title: String? = null,
    val text: String? = null,
    val warning: String? = null,
    val tpHint: TpHint? = null,
    val actions: List<QuestAction> = emptyList(),
    val postconditions: List<QuestCondition> = emptyList(),
    /**
     * Non-linear advancement: when all [jumpconditions] are met, the helper
     * jumps the current step index by [jumpOffset] (negative goes backward).
     * Used by quests that loop or branch based on dialog state.
     */
    val jumpconditions: List<QuestCondition> = emptyList(),
    val jumpOffset: Int = 0,
    val neededItems: List<QuestItemReq> = emptyList(),
    val recommendedItems: List<QuestItemReq> = emptyList(),
    /**
     * Optional id of a registered [com.projectx.quest.solver.QuestStepSolver].
     * When set, the solver runs each tick to produce dynamic actions / mark the
     * step solved. Used for puzzles where the correct highlight depends on
     * mutable game state (slide puzzles, dynamic orb positions, etc.).
     */
    val solverId: String? = null,
)

data class QuestItemReq(
    val name: String,
    val itemId: Int,
    val quantity: Int,
    val duringQuest: Boolean = false,
)

data class CombatNpcInfo(
    val name: String,
    val level: String,
    val quantity: Int,
)

/**
 * Captures `Types.QuestReq.*(...)` calls from the lua source.
 *
 *  - skill / ironmanOnlySkill: [name] = skill name, [level] = required level
 *  - combat: [level] = combat level
 *  - questpoints: [level] = required quest points
 *  - misc: [text] = free-form requirement description
 */
data class QuestReq(
    val type: String,
    val name: String? = null,
    val level: Int = 0,
    val text: String? = null,
    val ironmanOnly: Boolean = false,
)

/**
 * Per-step teleport hint. The `type` discriminates the icon variant
 * (`icon`, `text`, …); `hover` is the tooltip and `url` is an optional
 * asset name to render alongside.
 */
data class TpHint(
    val type: String,
    val hover: String,
    val url: String? = null,
)
