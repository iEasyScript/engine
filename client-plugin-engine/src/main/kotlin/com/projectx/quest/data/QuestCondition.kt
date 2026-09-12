package com.projectx.quest.data

sealed class QuestCondition {
    data class DistanceTo(val x: Double, val heightFine: Double, val y: Double, val range: Int, val instance: Boolean = false) : QuestCondition()
    data class DistanceFrom(val x: Double, val heightFine: Double, val y: Double, val range: Int, val instance: Boolean = false) : QuestCondition()
    data class DistanceToWithHeight(val x: Double, val heightFine: Double, val y: Double, val range: Int, val instance: Boolean = false) : QuestCondition()
    data class DistanceFromWithHeight(val x: Double, val heightFine: Double, val y: Double, val range: Int, val instance: Boolean = false) : QuestCondition()
    object NotInInstance : QuestCondition()
    object ChangedInstance : QuestCondition()
    object Manual : QuestCondition()
    object Always : QuestCondition()
    data class CaptureConversationState(val pattern: String, val key: String) : QuestCondition()
    data class StateEquals(val key: String, val value: String) : QuestCondition()
    data class InventoryContains(
        val itemId: Int,
        val displayName: String,
        val quantity: Int,
        val candidateItemTypeIds: List<Int> = emptyList(),
    ) : QuestCondition()
    data class InventoryDoesNotContain(
        val itemId: Int,
        val displayName: String,
        val candidateItemTypeIds: List<Int> = emptyList(),
    ) : QuestCondition()

    /**
     * Same tripartite identification scheme as [QuestAction.ModelHighlight]:
     * runtime matches the entity by [typeId] first, then [modelIds] (scene
     * scan against cache type modelIds), then [displayName].
     */
    data class ModelVisible(
        val kind: String,
        val typeId: Int,
        val modelIds: List<Int>,
        val candidateNpcTypeIds: List<Int> = emptyList(),
        val candidateObjectTypeIds: List<Int> = emptyList(),
        val displayName: String,
        val animated: Boolean = false,
        val instance: Boolean = false,
        val quantity: Int = -1,
        val atLocation: WorldLocation? = null,
    ) : QuestCondition()

    data class ModelNotVisible(
        val kind: String,
        val typeId: Int,
        val modelIds: List<Int>,
        val candidateNpcTypeIds: List<Int> = emptyList(),
        val candidateObjectTypeIds: List<Int> = emptyList(),
        val displayName: String,
        val instance: Boolean = false,
        val atLocation: WorldLocation? = null,
    ) : QuestCondition()

    object QuestStarted : QuestCondition()
    object QuestComplete : QuestCondition()
    object QuestInterfaceOpen : QuestCondition()
    /** True while the interface with [interfaceId] is open. [interfaceId] < 0 means unset (never matches). */
    data class InterfaceOpen(val interfaceId: Int = -1) : QuestCondition()

    /**
     * True when an NPC matching [typeId] (visibleId) is within [distance] tiles
     * (Chebyshev) of ([tileX], [tileY]) on [plane]. When [instance] is true the
     * tile is instance-local (a delta from the captured instance origin) and is
     * translated to absolute world coords at eval time. False when no such NPC exists.
     */
    data class NpcNearTile(
        val typeId: Int = -1,
        val displayName: String = "",
        val tileX: Int = 0,
        val tileY: Int = 0,
        val plane: Int = 0,
        val distance: Int = 2,
        val instance: Boolean = false,
        val candidateNpcTypeIds: List<Int> = emptyList(),
    ) : QuestCondition()
    object ConversationActive : QuestCondition()
    object ConversationInactive : QuestCondition()
    data class ConversationText(val text: String) : QuestCondition()
    data class ChatText(val text: String) : QuestCondition()
    object InInstance : QuestCondition()
    data class InCombatWith(val npcId: Int, val displayName: String = "") : QuestCondition()
    data class ItemClicked(val itemId: Int) : QuestCondition()
    object Generic : QuestCondition()
    data class Unknown(val name: String) : QuestCondition()
}
