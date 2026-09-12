package com.projectx.quest.overlay

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Priority
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.math.Vector2f
import com.projectx.game.math.Vector3f
import com.projectx.game.math.WorldToScreen
import com.projectx.game.nxt.HeightMap
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.inventories.Inventory
import com.projectx.quest.data.QuestAction
import com.projectx.quest.runtime.ActiveQuestState
import com.projectx.quest.runtime.QuestHighlightTick
import com.projectx.quest.runtime.QuestInstanceTracker
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.scopes.BackgroundDrawListScope
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.backend.rendering.ImGUIRender
import com.projectx.ui.highlight.InterfaceHighlight

object QuestOverlayRenderer {
    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        try {
            // StepAdvancer.maybeTick, ConditionEvaluator.tickSnapshot AND the interface-highlight
            // sync ([tickInterfaceHighlights]) all run on ClientMainLogic, not here - walking the
            // InterfaceList and reading component text (an EASTL getString) on the render thread
            // races the main thread rebuilding the dialogue, tearing the string's heap pointer ->
            // SIGSEGV in getString's NUL-scan. This render pass only draws from frozen snapshots.

            if (!UIState.questHelperEnabled.value || !UIState.questHelperShowOverlay.value ||
                Bootstrap.client.mainState != MainState.LOGGED_IN) {
                return
            }

            val quest = ActiveQuestState.selectedQuest
            val step = quest?.steps?.getOrNull(ActiveQuestState.currentStepIndex)
            if (quest == null || step == null) {
                return
            }

            // All quest highlights default to the same cyan as the dialogue highlights.
            val color = pulseAlpha(ImGuiColors.CYAN)
            // Solver actions are evaluated on the main-logic thread by QuestHighlightTick and
            // snapshotted here - reading game state on the render thread races scene mutation.
            val solverActions = QuestHighlightTick.solverOverlayActions
            val combinedActions = if (solverActions.isEmpty()) step.actions else step.actions + solverActions
            backgroundDrawList {
                combinedActions.forEach { action ->
                    safeRun {
                        when (action) {
                            is QuestAction.ModelHighlight -> highlightModel(action, color)
                            is QuestAction.Direction -> drawDirection(action, color)
                            is QuestAction.PathGuide -> drawPath(action, color)
                            is QuestAction.ConversationHighlight,
                            is QuestAction.InventoryHighlight,
                            is QuestAction.InterfaceComponentHighlight,
                            QuestAction.ContinueConversation,
                            QuestAction.ResetInstance,
                            is QuestAction.TextHint,
                            is QuestAction.Unknown -> Unit
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            println("[QuestOverlayRenderer] render error: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace()
        }
    }

    /**
     * Computes which interface components to highlight (dialog options, inventory slots, generic
     * components) and writes them into the [InterfaceHighlight] registry. Runs on the main-logic
     * tick - NOT the render thread - because it reads live component text (EASTL getString) and
     * walks the InterfaceList, which races the main thread's dialogue rebuild if done off-thread.
     * The render thread only reads the resulting registry (a ConcurrentHashMap) + rect ints.
     */
    @JvmStatic
    fun tickInterfaceHighlights() {
        try {
            if (!UIState.questHelperEnabled.value || !UIState.questHelperShowOverlay.value ||
                Bootstrap.client.mainState != MainState.LOGGED_IN) {
                clearStaleDialogHighlights()
                return
            }
            val quest = ActiveQuestState.selectedQuest
            val step = quest?.steps?.getOrNull(ActiveQuestState.currentStepIndex)
            if (quest == null || step == null) {
                clearStaleDialogHighlights()
                return
            }
            val solverActions = QuestHighlightTick.solverOverlayActions
            val combinedActions = if (solverActions.isEmpty()) step.actions else step.actions + solverActions
            safeRun { syncDialogHighlights(combinedActions) }
            safeRun { syncInventoryHighlights(combinedActions) }
            safeRun { syncInterfaceComponentHighlights(combinedActions) }
        } catch (t: Throwable) {
            println("[QuestOverlayRenderer] tickInterfaceHighlights error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private inline fun safeRun(block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            println("[QuestOverlayRenderer] action error: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace()
        }
    }

    /** Real ground fine-Z at a tile (player's plane), falling back to [fallback] when the map has no data. */
    private fun groundZ(tileX: Int, tileY: Int, fallback: Float): Float {
        val plane = runCatching { localPlayer.tile.plane }.getOrDefault(0)
        return HeightMap.fineHeight(plane, tileX * 512 + 256, tileY * 512 + 256)?.toFloat() ?: fallback
    }

    private fun BackgroundDrawListScope.highlightModel(action: QuestAction.ModelHighlight, color: Int) {
        if (action.kind == "item") return  // panel-only, no in-world label
        // Use the per-tick label snapshot published by [QuestHighlightTick]. Reading
        // live `npcManager` / cache types from the render thread races scene mutation
        // on the main thread and SIGSEGVs (witnessed when transitioning steps that
        // matched a transient model like the snow pile in violet-is-blue step 24).
        val labels = QuestHighlightTick.labels[action] ?: return
        for (label in labels) {
            // Scene objects have no working engine render-model outline, so highlight the tiles the
            // model occupies. NPCs publish no tiles (they get the engine outline) - label only.
            for (t in label.tiles) tile(t, color, label.fineZ)
            drawLabel(label, action.displayName, color)
        }
    }

    private fun BackgroundDrawListScope.drawLabel(
        label: QuestHighlightTick.Label,
        displayName: String,
        color: Int,
    ) {
        val fine = Vector3f(label.fineX, label.fineY, label.fineZ)
        val xy = runCatching { WorldToScreen.getEstimatedTileCenter(fine) }.getOrNull() ?: return
        val shown = if (isUsableLabel(displayName)) displayName
            else label.name.ifBlank { displayName }
        runCatching { text(xy.transform(0f, -32f), color, "-> $shown") }
    }

    private fun isUsableLabel(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("Model #") && name != "(unknown)"

    private fun BackgroundDrawListScope.drawDirection(d: QuestAction.Direction, color: Int) {
        // Translate instance-relative deltas to absolute world coords using the
        // captured instance origin. If the helper isn't in a known instance
        // (e.g., player walked into one between ticks) we skip rather than
        // drawing the marker at a misleading world location.
        val origin = if (d.instance) QuestInstanceTracker.origin ?: return else null
        val tileX = d.x.toInt() + (origin?.x ?: 0)
        val tileY = d.y.toInt() + (origin?.y ?: 0)
        // Instance-relative heightFine is measured from the local ground at the
        // captured instance origin (e.g. snowy hill in violet-is-blue sits at
        // fineZ ≈ 5000 above world Z=0). Add the captured origin Z so the tile
        // marker locks to the actual instance ground instead of floating below it.
        val tileZ = groundZ(tileX, tileY, d.heightFine.toFloat() + (origin?.fineZ ?: 0f))
        val targetFine = Vector3f(tileX * 512f + 256f, tileY * 512f + 256f, tileZ)

        // Suppress the marker once the player is within the auto-advance distance
        // hint - saves screen real estate when the player has clearly arrived.
        if (d.distance > 0) {
            val p = runCatching { localPlayer.tile }.getOrNull()
            if (p != null) {
                val dx = p.x - tileX; val dy = p.y - tileY
                if (dx * dx + dy * dy <= d.distance * d.distance) return
            }
        }

        tile(targetFine, color)

        val targetScreen = WorldToScreen.worldToScreen(targetFine)
        val (dw, dh) = NativeBridge.getDisplaySize()
        val screenCenter = Vector2f(dw / 2f, dh / 2f)

        if (targetScreen != null) {
            // `tile = true` is the lua hint that the target IS the exact tile (e.g.
            // a click-step on a trapdoor). The tile rectangle alone communicates
            // that more clearly than overlaying an arrow on it.
            if (!d.tile) arrow(screenCenter, targetScreen, color)
            val label = if (d.tile) "Stand on tile (${tileX}, ${tileY})"
                else "Go here (${tileX}, ${tileY})" +
                    (if (d.distance > 0) " - ${d.distance} tiles" else "")
            text(targetScreen.transform(0f, -32f), color, label)
        } else {
            val pTile = localPlayer.tile
            val bearing = bearingRad(pTile.x, pTile.y, tileX, tileY)
            offscreenBearingArrow(screenCenter, dw, dh, bearing, color)
        }
    }

    // Dialog interface -> ordered text-component ids for option rows. Used to map a
    // ConversationHighlight needle to a specific IFSlot we can hand off to InterfaceHighlight.
    //
    // The 5-option dialog interfaces (1188 / 1193 / 580) all flow through the shared CS2
    // procs `script3882` (lay out options) and `script3885` (wire click handlers); each
    // call passes 5 text-component refs as its option-text args. 720 is the long
    // chatbox option list (up to 10 rows) handled separately by the chatbox rebuild
    // procs (e.g. clientscript-10894 checks IF_GETHIDE on rows 4-13 of interface 720).
    private val DIALOG_OPTION_TEXT_COMPONENTS: Map<Int, IntArray> = mapOf(
        1188 to intArrayOf(6, 33, 35, 37, 39),
        1193 to intArrayOf(9, 16, 25, 31, 39),
        580 to intArrayOf(9, 16, 23, 30, 37),
        720 to intArrayOf(14, 21, 24, 27, 30, 33, 36, 39, 42, 45),
    )

    private const val DIALOG_HIGHLIGHT_KEY_PREFIX = "quest-helper-dialog-"

    private fun clearStaleDialogHighlights() {
        InterfaceHighlight.entries.keys
            .filter {
                it.startsWith(DIALOG_HIGHLIGHT_KEY_PREFIX) ||
                    it.startsWith(INVENTORY_HIGHLIGHT_KEY_PREFIX) ||
                    it.startsWith(COMPONENT_HIGHLIGHT_KEY_PREFIX)
            }
            .forEach { InterfaceHighlight.remove(it) }
    }

    private const val INVENTORY_HIGHLIGHT_KEY_PREFIX = "quest-helper-inv-"

    /**
     * Finds the inventory slot for each [QuestAction.InventoryHighlight] using the
     * same tripartite preference as in-world matching: itemId, then per-slot ItemType
     * modelId lookup, then case-insensitive name match. Registers each found slot
     * with [InterfaceHighlight] so the inventory tab renders an outline.
     */
    private fun syncInventoryHighlights(actions: List<QuestAction>) {
        val invs = actions.filterIsInstance<QuestAction.InventoryHighlight>()
        val seen = mutableSetOf<String>()
        if (invs.isNotEmpty()) {
            val inv = try { inventory } catch (_: Throwable) { null }
            if (inv != null) {
                invs.forEachIndexed { i, action ->
                    val slot = findInventorySlot(inv, action) ?: return@forEachIndexed
                    val key = "$INVENTORY_HIGHLIGHT_KEY_PREFIX$i"
                    seen += key
                    InterfaceHighlight.add(key, slot, color = ImGuiColors.CYAN, label = action.displayName)
                }
            }
        }
        InterfaceHighlight.entries.keys
            .filter { it.startsWith(INVENTORY_HIGHLIGHT_KEY_PREFIX) && it !in seen }
            .forEach { InterfaceHighlight.remove(it) }
    }

    /**
     * Match priority: primary itemId -> precomputed candidateItemTypeIds (offline
     * cache reverse-map for raw `Model.new(N)` references) -> case-insensitive
     * substring on the inventory slot's live name. No per-item ItemType cache
     * read here: that races other SQLite users on instance load.
     */
    private fun findInventorySlot(
        inv: Inventory,
        action: QuestAction.InventoryHighlight,
    ): IFSlot? {
        val items = inv.toList()
        val candidates: Set<Int> = buildSet {
            if (action.itemId >= 0) add(action.itemId)
            addAll(action.candidateItemTypeIds)
        }
        if (candidates.isNotEmpty()) {
            items.firstOrNull { it.id in candidates && it.amount > 0 }?.let { return it.slot }
        }
        val needle = action.displayName.takeIf { it.isNotBlank() }?.lowercase()
        if (needle != null) {
            items.firstOrNull { it.amount > 0 && it.name.lowercase().contains(needle) }?.let { return it.slot }
        }
        return null
    }

    private fun syncDialogHighlights(actions: List<QuestAction>) {
        val convo = actions.filterIsInstance<QuestAction.ConversationHighlight>()
        val seenKeys = mutableSetOf<String>()
        if (convo.isNotEmpty()) {
            convo.forEachIndexed { i, action ->
                val slot = findMatchingOptionSlot(action.text) ?: return@forEachIndexed
                val key = "$DIALOG_HIGHLIGHT_KEY_PREFIX$i"
                seenKeys += key
                InterfaceHighlight.add(key, slot, color = ImGuiColors.CYAN)
            }
        }
        // Remove any stale quest-helper dialog highlights from previous steps/frames.
        InterfaceHighlight.entries.keys
            .filter { it.startsWith(DIALOG_HIGHLIGHT_KEY_PREFIX) && it !in seenKeys }
            .forEach { InterfaceHighlight.remove(it) }
    }

    private const val COMPONENT_HIGHLIGHT_KEY_PREFIX = "quest-helper-comp-"

    /**
     * Registers an [InterfaceHighlight] for each [QuestAction.InterfaceComponentHighlight]
     * whose interface is open. Render-thread-safe (the highlight renderer resolves the
     * live component rect each frame); defaults to the shared quest-highlight cyan.
     */
    private fun syncInterfaceComponentHighlights(actions: List<QuestAction>) {
        val comps = actions.filterIsInstance<QuestAction.InterfaceComponentHighlight>()
        val seen = mutableSetOf<String>()
        if (comps.isNotEmpty()) {
            val interfaces = try { Bootstrap.client.interfaceList } catch (_: Throwable) { null }
            if (interfaces != null) {
                comps.forEachIndexed { i, a ->
                    val open = try { interfaces.isOpen(a.interfaceId) } catch (_: Throwable) { false }
                    if (!open) return@forEachIndexed
                    val key = "$COMPONENT_HIGHLIGHT_KEY_PREFIX$i"
                    seen += key
                    InterfaceHighlight.add(
                        key,
                        IFSlot(a.interfaceId, a.componentId, a.slotId),
                        color = a.color ?: ImGuiColors.CYAN,
                        label = a.label,
                    )
                }
            }
        }
        InterfaceHighlight.entries.keys
            .filter { it.startsWith(COMPONENT_HIGHLIGHT_KEY_PREFIX) && it !in seen }
            .forEach { InterfaceHighlight.remove(it) }
    }

    private fun findMatchingOptionSlot(needle: String): IFSlot? {
        if (needle.isBlank()) return null
        val n = needle.lowercase()
        val interfaces = try { Bootstrap.client.interfaceList } catch (_: Throwable) { return null }
        for ((iface, comps) in DIALOG_OPTION_TEXT_COMPONENTS) {
            // Gate on isOpen - interfaceList.getComponentRaw bypasses the visibility filter
            // and will happily read freed memory of a closed-but-cached interface (which
            // SIGSEGVs the JVM after teleports / area changes).
            val isOpen = try { interfaces.isOpen(iface) } catch (_: Throwable) { false }
            if (!isOpen) continue
            for (compId in comps) {
                val c = try { interfaces.getComponent(iface, compId) } catch (_: Throwable) { null } ?: continue
                val text = try { c.text } catch (_: Throwable) { continue }
                if (text.isBlank() || !text.lowercase().contains(n)) continue
                return IFSlot(iface, compId)
            }
        }
        return null
    }

    private fun BackgroundDrawListScope.drawPath(p: QuestAction.PathGuide, color: Int) {
        if (p.waypoints.isEmpty()) return
        // Translate instance-relative deltas to absolute world coords. Mirrors drawDirection:
        // if the helper isn't currently in a known instance, skip rather than rendering at a
        // misleading absolute location.
        val origin = if (p.instance) QuestInstanceTracker.origin ?: return else null
        val originX = origin?.x ?: 0
        val originY = origin?.y ?: 0
        val originZ = origin?.fineZ ?: 0f

        val centers = mutableListOf<Vector2f>()
        for (wp in p.waypoints) {
            val tileX = wp.x + originX
            val tileY = wp.y + originY
            // Same instance-Z offset story as drawDirection - locks instance-relative
            // waypoints to the actual ground height of the instance scene.
            val fine = Vector3f(
                tileX.toFloat() * 512f + 256f,
                tileY.toFloat() * 512f + 256f,
                groundZ(tileX.toInt(), tileY.toInt(), wp.heightFine.toFloat() + originZ),
            )
            tile(fine, color)
            WorldToScreen.worldToScreen(fine)?.let { centers += it }
        }
        if (centers.size >= 2) {
            val pts = FloatArray(centers.size * 2)
            centers.forEachIndexed { i, c -> pts[i * 2] = c.x; pts[i * 2 + 1] = c.y }
            polyLine(pts, color, 0, 3f)
        }
    }
}
