package com.projectx.quest.editor

import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.QuestItemReq
import com.projectx.quest.data.QuestStep
import com.projectx.quest.data.WorldLocation

/**
 * Mutable draft representation for in-place quest editing. The runtime model
 * ([Quest], [QuestStep], etc.) is immutable so that the renderer/advancer can
 * read a consistent snapshot without locking; the editor builds a draft on top
 * of it and only swaps the immutable cached copy when the user clicks Save.
 *
 * Convert via [toQuest] / [fromQuest]. Round-trips losslessly for every action
 * / condition variant the JSON deserializer recognises.
 */
class MutableQuest(
    var slug: String,
    var name: String,
    var members: Boolean = false,
    var length: String? = null,
    var timeline: String? = null,
    var releaseDate: Long = 0L,
    var stageVarbit: Int = -1,
    var stageVarbitCompleteValue: Int = -1,
    var cacheQuestId: Int = -1,
    val neededItems: MutableList<QuestItemReq> = mutableListOf(),
    val recommendedItems: MutableList<QuestItemReq> = mutableListOf(),
    val steps: MutableList<MutableStep> = mutableListOf(),
) {
    fun toQuest(): Quest = Quest(
        slug = slug,
        name = name,
        members = members,
        length = length,
        timeline = timeline,
        releaseDate = releaseDate,
        neededItems = neededItems.toList(),
        recommendedItems = recommendedItems.toList(),
        combatNPCs = emptyList(),
        prereqQuests = emptyList(),
        questReqs = emptyList(),
        steps = steps.map { it.toStep() },
        stageVarbit = stageVarbit,
        stageVarbitCompleteValue = stageVarbitCompleteValue,
        cacheQuestId = cacheQuestId,
    )

    companion object {
        fun fromQuest(q: Quest): MutableQuest = MutableQuest(
            slug = q.slug,
            name = q.name,
            members = q.members,
            length = q.length,
            timeline = q.timeline,
            releaseDate = q.releaseDate,
            stageVarbit = q.stageVarbit,
            stageVarbitCompleteValue = q.stageVarbitCompleteValue,
            cacheQuestId = q.cacheQuestId,
            neededItems = q.neededItems.toMutableList(),
            recommendedItems = q.recommendedItems.toMutableList(),
            steps = q.steps.map { MutableStep.fromStep(it) }.toMutableList(),
        )
    }
}

class MutableStep(
    var title: String? = null,
    var text: String? = null,
    var warning: String? = null,
    var solverId: String? = null,
    var jumpOffset: Int = 0,
    val actions: MutableList<QuestAction> = mutableListOf(),
    val postconditions: MutableList<QuestCondition> = mutableListOf(),
    val jumpconditions: MutableList<QuestCondition> = mutableListOf(),
    val neededItems: MutableList<QuestItemReq> = mutableListOf(),
    val recommendedItems: MutableList<QuestItemReq> = mutableListOf(),
) {
    fun toStep(): QuestStep = QuestStep(
        title = title?.takeIf { it.isNotEmpty() },
        text = text?.takeIf { it.isNotEmpty() },
        warning = warning?.takeIf { it.isNotEmpty() },
        actions = actions.toList(),
        postconditions = postconditions.toList(),
        jumpconditions = jumpconditions.toList(),
        jumpOffset = jumpOffset,
        neededItems = neededItems.toList(),
        recommendedItems = recommendedItems.toList(),
        solverId = solverId?.takeIf { it.isNotEmpty() },
    )

    companion object {
        fun fromStep(s: QuestStep): MutableStep = MutableStep(
            title = s.title,
            text = s.text,
            warning = s.warning,
            solverId = s.solverId,
            jumpOffset = s.jumpOffset,
            actions = s.actions.toMutableList(),
            postconditions = s.postconditions.toMutableList(),
            jumpconditions = s.jumpconditions.toMutableList(),
            neededItems = s.neededItems.toMutableList(),
            recommendedItems = s.recommendedItems.toMutableList(),
        )
    }
}

/** Factory helpers — produce default-shaped instances ready for inline editing. */
object QuestActionFactory {
    fun direction(x: Int, y: Int, heightFine: Double = 0.0) = QuestAction.Direction(
        x = x.toDouble(),
        heightFine = heightFine,
        y = y.toDouble(),
        instance = false,
        tile = false,
        distance = -1,
    )
    fun modelHighlight(kind: String = "object", name: String = "") = QuestAction.ModelHighlight(
        kind = kind,
        typeId = -1,
        modelIds = emptyList(),
        displayName = name,
    )
    fun conversationHighlight() = QuestAction.ConversationHighlight(text = "")
    fun inventoryHighlight() = QuestAction.InventoryHighlight(
        itemId = -1,
        modelIds = emptyList(),
        displayName = "",
    )
    fun continueConversation() = QuestAction.ContinueConversation
    fun resetInstance() = QuestAction.ResetInstance
    fun pathGuide() = QuestAction.PathGuide(waypoints = emptyList())
    fun textHint(text: String = "") = QuestAction.TextHint(text)
    fun interfaceComponentHighlight() = QuestAction.InterfaceComponentHighlight(interfaceId = -1, componentId = -1)
}

object QuestConditionFactory {
    fun distanceTo(x: Int, y: Int, range: Int = 2, heightFine: Double = 0.0) = QuestCondition.DistanceTo(
        x.toDouble(), heightFine, y.toDouble(), range, false,
    )
    fun distanceFrom(x: Int, y: Int, range: Int = 5, heightFine: Double = 0.0) = QuestCondition.DistanceFrom(
        x.toDouble(), heightFine, y.toDouble(), range, false,
    )
    fun inventoryContains(itemId: Int = -1, name: String = "", qty: Int = 1) =
        QuestCondition.InventoryContains(itemId, name, qty)
    fun inventoryDoesNotContain(itemId: Int = -1, name: String = "") =
        QuestCondition.InventoryDoesNotContain(itemId, name)
    fun modelVisible(kind: String = "npc", name: String = "") =
        QuestCondition.ModelVisible(kind = kind, typeId = -1, modelIds = emptyList(), displayName = name)
    fun modelNotVisible(kind: String = "npc", name: String = "") =
        QuestCondition.ModelNotVisible(kind = kind, typeId = -1, modelIds = emptyList(), displayName = name)
    fun conversationText(text: String = "") = QuestCondition.ConversationText(text)
    fun chatText(text: String = "") = QuestCondition.ChatText(text)
}

/** Replace x/y/heightFine of a Direction (immutable copy). */
fun QuestAction.Direction.withLocation(x: Double, y: Double, heightFine: Double): QuestAction.Direction =
    copy(x = x, y = y, heightFine = heightFine)

/** Replace location of a ModelHighlight (immutable copy). */
fun QuestAction.ModelHighlight.withAtLocation(loc: WorldLocation?): QuestAction.ModelHighlight =
    copy(atLocation = loc)
