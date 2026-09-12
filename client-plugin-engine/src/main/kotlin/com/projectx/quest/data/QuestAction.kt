package com.projectx.quest.data

sealed class QuestAction {
    /**
     * In-world arrow/tile guide.
     *
     * If [instance] is true, [x] and [y] are deltas relative to the instance
     * origin captured the first frame the player enters the instance area
     * (X >= 6400 in RS3). The renderer adds the captured origin to project the
     * absolute world target.
     *
     * [tile] hints that the position should snap exactly to a tile; renderers
     * draw a tile square instead of just an arrow. [distance] (-1 = unset) is
     * the lua-side advance distance hint (auto-advance triggers when the
     * player is within that range of the target).
     */
    data class Direction(
        val x: Double,
        val heightFine: Double,
        val y: Double,
        val instance: Boolean = false,
        val tile: Boolean = false,
        val distance: Int = -1,
    ) : QuestAction()

    /**
     * In-world entity highlight.
     *
     * Tripartite identification — the renderer tries each in order:
     *   1. [typeId] (NPC/Object/Item type id from Models.npcs/objects/items lookup)
     *   2. [modelIds] (raw Model.new(N) ids — runtime scans scene entities whose
     *      cache modelIds list contains any of these)
     *   3. [displayName] (case-insensitive contains match on the scene entity's name)
     *
     * Any of the three can be missing ([typeId] = -1, [modelIds] empty, blank
     * [displayName]); rendering falls through to the next strategy.
     *
     * [kind] is a hint ("npc"/"object"/"item"/"model") that narrows which scene
     * list to search first when matching by name or model id.
     */
    data class ModelHighlight(
        val kind: String,
        val typeId: Int,
        val modelIds: List<Int>,
        val candidateNpcTypeIds: List<Int> = emptyList(),
        val candidateObjectTypeIds: List<Int> = emptyList(),
        val displayName: String,
        val priority: String? = null,
        val instance: Boolean = false,
        val distance: Int = -1,
        val atLocation: WorldLocation? = null,
    ) : QuestAction()

    data class ConversationHighlight(val text: String) : QuestAction()
    /**
     * Inventory slot highlight. Identification follows the same tripartite
     * scheme as [ModelHighlight]:
     *   1. [itemId] — direct match on inventory item id.
     *   2. [modelIds] — runtime scan: for each visible inventory slot, look up
     *      its [world.gregs.voidps.cache.type.data.ObjType] and check if its modelId
     *      appears in this list. Used when lua quest source referenced the item
     *      via `Model.new(N)` rather than `Models.items[...]`.
     *   3. [displayName] — case-insensitive substring match on slot name.
     */
    data class InventoryHighlight(
        val itemId: Int,
        val modelIds: List<Int>,
        val candidateItemTypeIds: List<Int> = emptyList(),
        val displayName: String,
    ) : QuestAction()
    object ContinueConversation : QuestAction()
    object ResetInstance : QuestAction()

    /**
     * Highlight a specific interface component the user should click — a puzzle
     * button, slot, or widget. Rendered render-thread-safely via
     * [com.projectx.ui.highlight.InterfaceHighlight], gated on the interface being
     * open. Primarily emitted by [com.projectx.quest.solver.QuestStepSolver]s to
     * point at the exact control to press; also usable as a static step action.
     */
    data class InterfaceComponentHighlight(
        val interfaceId: Int,
        val componentId: Int,
        val slotId: Int = -1,
        val label: String = "",
        /** RGBA outline colour; null = the default quest-highlight cyan. */
        val color: Int? = null,
    ) : QuestAction()

    /**
     * Multi-tile path overlay.
     *
     * If [instance] is true, each waypoint's [Waypoint.x] / [Waypoint.y] are
     * deltas relative to the captured [com.projectx.quest.runtime.QuestInstanceTracker]
     * origin (same convention as [Direction]). Without the flag, coordinates
     * are absolute tile positions.
     */
    data class PathGuide(val waypoints: List<Waypoint>, val instance: Boolean = false) : QuestAction() {
        data class Waypoint(val x: Double, val heightFine: Double, val y: Double)
    }

    /**
     * Free-form text instruction. Used primarily by [com.projectx.quest.solver.QuestStepSolver]
     * implementations to surface dynamic state-driven hints in the helper panel — e.g.
     * "Submit 2 discs: Yellow Square (12) + Red Triangle (3)" for the Eyes of Glouphrie
     * puzzle. Renders as a bulleted line in the panel's "Next:" block; no overlay or
     * world-position component.
     */
    data class TextHint(val text: String) : QuestAction()

    data class Unknown(val name: String) : QuestAction()
}

/**
 * Optional anchor for a [QuestAction.ModelHighlight] or
 * [QuestCondition.ModelVisible] — restricts the match to entities at this
 * position. When the action is flagged [QuestAction.ModelHighlight.instance],
 * the x/y are deltas from the instance origin (same as Direction/PathGuide).
 */
data class WorldLocation(val x: Double, val heightFine: Double, val y: Double)
