package com.projectx.quest.runtime

import com.projectx.game.hooks.impl.InterfaceTextCapture
import com.projectx.quest.data.QuestCondition
import com.projectx.script.api.dialogueOptions
import com.projectx.script.api.inInstancedArea
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.localPlayer
import com.projectx.script.api.varps

object ConditionEvaluator {
    private val DIALOG_INTERFACES_SET = InterfaceTextCapture.DIALOG_INTERFACES

    @Volatile var debugCaptureEnabled: Boolean = false
    @Volatile var lastDialogCapture: List<DialogCapture> = emptyList()
        private set

    @Volatile private var dialogSnapshot: List<DialogCapture> = emptyList()

    data class DialogCapture(val interfaceId: Int, val sample: String)

    @Volatile private var lastDebugLogMs: Long = 0L
    private const val DEBUG_LOG_THROTTLE_MS = 2000L

    /** Quest start/offer window ("Would you like to begin?") - distinct from ordinary NPC dialogue. */
    private const val QUEST_START_INTERFACE = 1500

    fun isMet(c: QuestCondition): Boolean = try {
        when (c) {
            is QuestCondition.DistanceTo -> playerWithinAbsolute(c.x.toInt(), c.y.toInt(), c.range, c.instance)
            is QuestCondition.DistanceFrom -> !playerWithinAbsolute(c.x.toInt(), c.y.toInt(), c.range, c.instance)
            is QuestCondition.DistanceToWithHeight -> playerWithinWithHeightAbsolute(c.x.toInt(), c.y.toInt(), c.heightFine, c.range, c.instance)
            is QuestCondition.DistanceFromWithHeight -> !playerWithinWithHeightAbsolute(c.x.toInt(), c.y.toInt(), c.heightFine, c.range, c.instance)

            is QuestCondition.InventoryContains -> inventoryCount(c.itemId, c.candidateItemTypeIds, c.displayName, exactNameOnly = false) >= c.quantity
            is QuestCondition.InventoryDoesNotContain -> inventoryCount(c.itemId, c.candidateItemTypeIds, c.displayName, exactNameOnly = true) == 0

            is QuestCondition.ModelVisible -> {
                val spec = QuestTargetMatcher.specFor(c)
                if (c.quantity > 0) QuestTargetMatcher.countAtLeast(spec, c.quantity)
                else QuestTargetMatcher.isPresent(spec)
            }
            is QuestCondition.ModelNotVisible -> !QuestTargetMatcher.isPresent(QuestTargetMatcher.specFor(c))

            QuestCondition.QuestInterfaceOpen -> interfaces.isOpen(QUEST_START_INTERFACE)
            is QuestCondition.InterfaceOpen -> c.interfaceId >= 0 && interfaces.isOpen(c.interfaceId)
            is QuestCondition.NpcNearTile -> {
                val abs = absoluteTile(c.tileX, c.tileY, c.instance)
                abs != null && QuestTargetMatcher.anyNpcNearTile(QuestTargetMatcher.specFor(c), abs.first, abs.second, c.plane, c.distance)
            }
            QuestCondition.ConversationActive -> isDialogOpen()
            QuestCondition.ConversationInactive -> !isDialogOpen()
            is QuestCondition.ConversationText -> dialogContainsText(c.text)
            is QuestCondition.ChatText -> RecentChat.containsRecent(c.text)

            QuestCondition.QuestStarted -> {
                val vb = ActiveQuestState.selectedQuest?.stageVarbit ?: -1
                vb >= 0 && varps.getVarBit(vb) > 0
            }
            QuestCondition.QuestComplete -> {
                val q = ActiveQuestState.selectedQuest
                val vb = q?.stageVarbit ?: -1
                val complete = q?.stageVarbitCompleteValue ?: -1
                vb >= 0 && complete > 0 && varps.getVarBit(vb) >= complete
            }

            QuestCondition.InInstance -> inInstancedArea
            QuestCondition.NotInInstance -> !inInstancedArea
            QuestCondition.Always -> true
            QuestCondition.Manual -> false
            QuestCondition.ChangedInstance -> false  // requires tracking across frames; manual only
            is QuestCondition.CaptureConversationState -> false  // not implemented
            is QuestCondition.StateEquals -> false  // not implemented
            is QuestCondition.InCombatWith -> false
            is QuestCondition.ItemClicked -> false
            QuestCondition.Generic -> false
            is QuestCondition.Unknown -> false
        }
    } catch (_: Throwable) {
        false
    }

    fun implementable(c: QuestCondition): Boolean = when (c) {
        is QuestCondition.DistanceTo,
        is QuestCondition.DistanceFrom,
        is QuestCondition.DistanceToWithHeight,
        is QuestCondition.DistanceFromWithHeight,
        is QuestCondition.InventoryContains,
        is QuestCondition.InventoryDoesNotContain,
        is QuestCondition.ModelVisible,
        is QuestCondition.ModelNotVisible,
        QuestCondition.QuestInterfaceOpen,
        is QuestCondition.InterfaceOpen,
        is QuestCondition.NpcNearTile,
        QuestCondition.ConversationActive,
        QuestCondition.ConversationInactive,
        is QuestCondition.ConversationText,
        is QuestCondition.ChatText,
        QuestCondition.InInstance,
        QuestCondition.NotInInstance,
        QuestCondition.Always -> true

        QuestCondition.QuestStarted,
        QuestCondition.QuestComplete -> (ActiveQuestState.selectedQuest?.stageVarbit ?: -1) >= 0

        QuestCondition.Manual,
        QuestCondition.ChangedInstance,
        is QuestCondition.CaptureConversationState,
        is QuestCondition.StateEquals,
        is QuestCondition.InCombatWith,
        is QuestCondition.ItemClicked,
        QuestCondition.Generic,
        is QuestCondition.Unknown -> false
    }

    fun describe(c: QuestCondition): String = when (c) {
        is QuestCondition.DistanceTo -> "Within ${c.range} tiles of (${c.x.toInt()}, ${c.y.toInt()})"
        is QuestCondition.DistanceFrom -> "More than ${c.range} tiles from (${c.x.toInt()}, ${c.y.toInt()})"
        is QuestCondition.DistanceToWithHeight -> "Within ${c.range} tiles of (${c.x.toInt()}, ${c.y.toInt()}) at height ${c.heightFine.toInt()}"
        is QuestCondition.DistanceFromWithHeight -> "More than ${c.range} tiles from (${c.x.toInt()}, ${c.y.toInt()}) at height ${c.heightFine.toInt()}"
        QuestCondition.NotInInstance -> "Not in an instance"
        QuestCondition.ChangedInstance -> "Changed instance"
        QuestCondition.Manual -> "(manual advance)"
        QuestCondition.Always -> "(always true)"
        is QuestCondition.CaptureConversationState -> "Capture conversation state: $${c.key}"
        is QuestCondition.StateEquals -> "State ${c.key} == ${c.value}"
        is QuestCondition.InventoryContains -> "Inventory contains ${c.quantity}× ${c.displayName}"
        is QuestCondition.InventoryDoesNotContain -> "Inventory does NOT contain ${c.displayName}"
        is QuestCondition.ModelVisible -> "${c.displayName} visible"
        is QuestCondition.ModelNotVisible -> "${c.displayName} not visible"
        QuestCondition.QuestStarted -> "Quest started (manual advance)"
        QuestCondition.QuestComplete -> "Quest complete (manual advance)"
        QuestCondition.QuestInterfaceOpen -> "Quest dialog open"
        is QuestCondition.InterfaceOpen -> if (c.interfaceId < 0) "Interface open (set id)" else "Interface ${c.interfaceId} open"
        is QuestCondition.NpcNearTile -> "${c.displayName.ifBlank { "NPC #${c.typeId}" }} within ${c.distance} of (${c.tileX}, ${c.tileY})${if (c.instance) " [instance-local]" else ""}"
        QuestCondition.ConversationActive -> "Conversation active"
        QuestCondition.ConversationInactive -> "Conversation inactive"
        is QuestCondition.ConversationText -> "Dialog says: \"${c.text.take(60)}\""
        is QuestCondition.ChatText -> "Chat says: \"${c.text.take(60)}\""
        QuestCondition.InInstance -> "Player in instance"
        is QuestCondition.InCombatWith -> "In combat with ${
            if (c.displayName.isNotBlank()) c.displayName
            else if (c.npcId >= 0) "NPC #${c.npcId}"
            else "(unknown enemy)"
        }"
        is QuestCondition.ItemClicked -> "Item #${c.itemId} clicked"
        QuestCondition.Generic -> "(generic - manual advance)"
        is QuestCondition.Unknown -> "(unsupported: ${c.name})"
    }

    /**
     * Resolves the absolute target tile from a Distance condition's coords, honoring
     * the instance flag - when instance=true the lua coords are deltas from the
     * captured instance origin (same convention as Direction/PathGuide). Returns null
     * if the condition needed an instance origin but one isn't currently tracked, so
     * the condition is treated as unmet rather than evaluated against a stale world
     * position.
     */
    private fun absoluteTile(tileX: Int, tileY: Int, instance: Boolean): Pair<Int, Int>? {
        if (!instance) return tileX to tileY
        val origin = QuestInstanceTracker.origin ?: return null
        return (tileX + origin.x) to (tileY + origin.y)
    }

    private fun playerWithinAbsolute(tileX: Int, tileY: Int, range: Int, instance: Boolean): Boolean {
        val (ax, ay) = absoluteTile(tileX, tileY, instance) ?: return false
        return playerWithin(ax, ay, range)
    }

    private fun playerWithin(tileX: Int, tileY: Int, range: Int): Boolean {
        val p = localPlayer.tile
        val dx = p.x - tileX
        val dy = p.y - tileY
        // Honor `range = 0` as "exactly on this tile" - the bolt lua uses it for the
        // violet-is-blue ice-slide step targets where Chebyshev≤1 would auto-advance
        // before the player actually lands on the marker. Negative range is treated
        // as 0 to keep `coerce(>=0)` semantics consistent.
        val r = range.coerceAtLeast(0)
        return dx in -r..r && dy in -r..r
    }

    /**
     * Total inventory count across the primary [itemId] and any precomputed
     * [candidateItemTypeIds]. Falls back to a name match on the visible inventory
     * slot names when no numeric id resolved - covers `frozenBucketItem` / aliased
     * items the cache reverse-map can't find.
     *
     * [exactNameOnly] flips the name-fallback semantics. `InventoryContains` wants
     * to be lenient (substring) so `"Bucket"` matches `"Empty bucket"`. But
     * `InventoryDoesNotContain` MUST be exact - substring leniency means owning a
     * different bucket-named item blocks auto-advance forever (we'd report
     * count > 0 even though the specific item is gone).
     */
    private fun inventoryCount(
        itemId: Int,
        candidateItemTypeIds: List<Int>,
        displayName: String,
        exactNameOnly: Boolean,
    ): Int {
        var total = 0
        if (itemId >= 0) total += runCatching { inventory.count(itemId) }.getOrDefault(0)
        if (candidateItemTypeIds.isNotEmpty()) {
            val ids = candidateItemTypeIds.toIntArray()
            total += runCatching { inventory.count(*ids) }.getOrDefault(0)
        }
        if (total == 0) {
            val needle = displayName.trim().takeIf { it.isNotBlank() }?.lowercase()
            if (needle != null) {
                total += runCatching {
                    inventory.filter {
                        it.amount > 0 && run {
                            val name = it.name.lowercase()
                            if (exactNameOnly) name == needle else name.contains(needle)
                        }
                    }.sumOf { it.amount }
                }.getOrDefault(0)
            }
        }
        return total
    }

    // Lua's heightFine is the target's world Y in fine units (the upstream check does a
    // 3D euclidean distance against the player's position). We don't have a clean fine-Y
    // for the player, but plane is enough to disambiguate ground-floor vs upper-floor steps:
    // heightFine < ~700 -> ground plane; otherwise require the player to be on a non-zero plane.
    private fun playerWithinWithHeightAbsolute(tileX: Int, tileY: Int, heightFine: Double, range: Int, instance: Boolean): Boolean {
        val (ax, ay) = absoluteTile(tileX, tileY, instance) ?: return false
        if (!playerWithin(ax, ay, range)) return false
        val playerPlane = localPlayer.tile.plane
        val upperFloor = heightFine > 700.0
        return if (upperFloor) playerPlane > 0 else playerPlane == 0
    }

    /**
     * Reads the passive [InterfaceTextCapture] map (populated by the binary's own
     * DrawSlotChildren walk) - no live InterfaceList traversal, no visibility checks,
     * no peer-component dereferences from our thread. The text was captured while the
     * binary held the components stable for its own iteration; we just match strings.
     */
    private fun dialogContainsText(target: String): Boolean {
        if (target.isBlank()) return false
        val needle = normalize(target)
        if (needle.isEmpty()) return false

        for (text in dialogueOptions.keys) {
            if (normalize(text).contains(needle)) return true
        }

        val combined = InterfaceTextCapture.textFor(DIALOG_INTERFACES_SET)
        val hit = normalize(combined).contains(needle)

        if (debugCaptureEnabled) {
            val samples = InterfaceTextCapture.samplesByInterface(DIALOG_INTERFACES_SET)
            lastDialogCapture = samples.map { DialogCapture(it.key, it.value) }
            if (!hit) {
                val now = System.currentTimeMillis()
                if (now - lastDebugLogMs > DEBUG_LOG_THROTTLE_MS) {
                    lastDebugLogMs = now
                    println("[ConditionEvaluator] dialog MISS needle=\"${target.take(80)}\" - captures:")
                    if (samples.isEmpty()) {
                        println("  (no dialog interfaces produced text)")
                    } else {
                        for ((iface, sample) in samples) {
                            println("  iface=$iface text=\"${sample.take(200).trim()}\"")
                        }
                    }
                }
            }
        }
        return hit
    }

    /**
     * Republishes [InterfaceTextCapture]'s per-interface samples as a [DialogCapture]
     * list for the editor's debug HUD. No live walking - just a read of the passive
     * capture map. Gated on demand so debug-off ticks do zero work.
     */
    fun tickSnapshot() {
        val sweepRange = pendingSweep
        if (!debugCaptureEnabled && sweepRange == null && !pendingLogState) {
            if (dialogSnapshot.isNotEmpty()) dialogSnapshot = emptyList()
            return
        }
        val samples = InterfaceTextCapture.samplesByInterface(DIALOG_INTERFACES_SET)
        dialogSnapshot = samples.map { DialogCapture(it.key, it.value) }
        if (pendingLogState) {
            pendingLogState = false
            println("[ConditionEvaluator] logDialogState - ${samples.size} dialog interfaces with text")
            for ((iface, sample) in samples) {
                println("  iface=$iface text=\"${sample.take(300).trim()}\"")
            }
        }
        if (sweepRange != null) {
            pendingSweep = null
            val widened = (sweepRange.first..sweepRange.last).toSet()
            val sweep = InterfaceTextCapture.samplesByInterface(widened)
            println("[ConditionEvaluator] sweep($sweepRange) - ${sweep.size} interfaces with text")
            for ((iface, sample) in sweep) {
                val marker = if (iface in DIALOG_INTERFACES_SET) "  " else "* "
                println("$marker iface=$iface text=\"${sample.take(250).trim()}\"")
            }
            println("[ConditionEvaluator] sweep done - '*' = not in DIALOG_INTERFACES_SET")
        }
    }

    fun captureAllDialogText(): List<DialogCapture> = dialogSnapshot

    @Volatile private var pendingSweep: IntRange? = null
    @Volatile private var pendingLogState: Boolean = false

    fun requestSweep(range: IntRange = 0..2000) { pendingSweep = range }
    fun requestLogState() { pendingLogState = true }
    fun logDialogState() { requestLogState() }
    fun sweepAllInterfaces(range: IntRange = 0..2000) { requestSweep(range) }

    private val MARKUP_TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Lower-cases and strips RS markup tags (`<br>`, `<p=2>`, `<col=...>`) plus all
     * whitespace, so a quest needle matches the dialogue regardless of the line
     * wrapping or styling the client injects - e.g. a `<br>` the client drops between
     * "north of" and "Falador" must not break a `"north of Falador and"` match.
     */
    private fun normalize(s: String): String =
        s.replace(MARKUP_TAG, "").replace(WHITESPACE, "").lowercase()
}
