package com.projectx.quest.runtime

import org.projectx.core.game.skill.Skill
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.data.QuestReq
import com.projectx.script.api.getRealLevel
import com.projectx.script.api.varps

enum class QuestStatus { UNKNOWN, NOT_STARTED, IN_PROGRESS, COMPLETED }

object QuestStatusEvaluator {
    private const val QUEST_POINTS_VARP = 1297
    private const val IRONMAN_VARBIT = 20806

    fun statusOf(q: Quest): QuestStatus = try {
        val vb = q.stageVarbit
        val complete = q.stageVarbitCompleteValue
        if (vb < 0) {
            QuestStatus.UNKNOWN
        } else {
            val v = varps.getVarBit(vb)
            when {
                complete > 0 && v >= complete -> QuestStatus.COMPLETED
                v > 0 -> QuestStatus.IN_PROGRESS
                else -> QuestStatus.NOT_STARTED
            }
        }
    } catch (_: Throwable) {
        QuestStatus.UNKNOWN
    }

    fun isLocked(q: Quest): Boolean = try {
        if (q.members && !isMember()) return true
        if (q.questReqs.any { isReqUnmet(it) }) return true
        q.prereqQuests.any { isPrereqUnmet(it) }
    } catch (_: Throwable) {
        false
    }

    fun isReqUnmet(req: QuestReq): Boolean = when (req.type) {
        "skill" -> isSkillUnmet(req)
        "ironmanOnlySkill" -> isIronman() && isSkillUnmet(req)
        "questpoints" -> runCatching { varps.getVar(QUEST_POINTS_VARP) }.getOrDefault(0) < req.level
        "combat", "misc" -> false
        else -> false
    }

    fun isPrereqUnmet(name: String): Boolean {
        val prereq = QuestLibrary.byName(name) ?: return false
        return statusOf(prereq) != QuestStatus.COMPLETED
    }

    fun isMembersBlocked(q: Quest): Boolean = q.members && !isMember()

    private fun isSkillUnmet(req: QuestReq): Boolean {
        val skill = resolveSkill(req.name ?: return false) ?: return false
        val have = runCatching { getRealLevel(skill) }.getOrDefault(0)
        return have < req.level
    }

    private fun resolveSkill(name: String): Skill? {
        val normalized = name.trim().uppercase().let { if (it == "DEFENCE") "DEFENSE" else it }
        return Skill.entries.firstOrNull { it.name == normalized }
    }

    private fun isIronman(): Boolean = try {
        varps.getVarBit(IRONMAN_VARBIT) == 1
    } catch (_: Throwable) {
        false
    }

    // TODO: bind the CS2 PLAYERMEMBER opcode (or locate a mirroring varbit) to gate
    // members-only quests for f2p accounts. For now the bot only ever runs on member
    // accounts, so reporting true matches reality and avoids hiding the whole list.
    private fun isMember(): Boolean = true
}
