package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Extra
import world.gregs.voidps.cache.type.Parameterized
import world.gregs.voidps.cache.type.OpcodeOrdered
import world.gregs.voidps.cache.type.ParamRecord

/** A var whose value moves from [startedValue] to [completedValue] as the quest is played. */
data class QuestVarProgress(val varId: Int, val startedValue: Int, val completedValue: Int)

/** A var gate the player must satisfy, with the journal line [text] shown while unmet. */
data class QuestVarRequirement(val varId: Int, val min: Int, val max: Int, val text: String)

/** [skill] indexes `org.projectx.core.game.skill.Skill`. */
data class QuestSkillRequirement(val skill: Int, val level: Int)

data class QuestType(
    override var id: Int = -1,
    var name: String? = null,
    var sortName: String? = null,
    var varpProgress: List<QuestVarProgress>? = null,
    var varbitProgress: List<QuestVarProgress>? = null,
    var parentQuest: Int = -1,
    var unknown6: Int = -1,
    var difficulty: Int = -1,
    var unknown8: Boolean = false,
    var questPoints: Int = -1,
    var startTiles: IntArray? = null,
    var unknownTile: Int = -1,
    var questRequirements: List<Int>? = null,
    var skillRequirements: List<QuestSkillRequirement>? = null,
    var questPointRequirement: Int = 0,
    var graphicId: Int = -1,
    var varpRequirements: List<QuestVarRequirement>? = null,
    var varbitRequirements: List<QuestVarRequirement>? = null,
    override var params: Map<Int, Any>? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null,
) : CacheType, Parameterized, Extra, OpcodeOrdered {
    override var paramRecords: List<ParamRecord>? = null
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    companion object {
        val EMPTY = QuestType()
    }
}
