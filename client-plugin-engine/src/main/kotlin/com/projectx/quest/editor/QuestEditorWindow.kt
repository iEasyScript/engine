package com.projectx.quest.editor

import com.projectx.game.hooks.Priority
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.QuestItemReq
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.data.WorldLocation
import com.projectx.quest.runtime.ActiveQuestState
import com.projectx.quest.runtime.ConditionEvaluator
import com.projectx.quest.runtime.QuestInstanceTracker
import com.projectx.quest.solver.QuestSolverRegistry
import com.projectx.ui.backend.dsl.ImGuiDsl.setNextWindowPos
import com.projectx.ui.backend.dsl.ImGuiDsl.setNextWindowSize
import com.projectx.ui.backend.dsl.ImGuiDsl.window
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.flags.ImGuiCond
import com.projectx.ui.backend.flags.WindowFlags
import com.projectx.ui.backend.rendering.ImGUIRender
import com.projectx.ui.backend.dsl.utils.ImGuiDir

/**
 * Separated ImGui window for live editing the currently-selected quest.
 * Pops as a floating panel next to the main UI; visible whenever
 * [QuestEditorState.windowOpen] is true.
 *
 * The window covers every action / condition variant the schema understands.
 * Editing operates on a [MutableQuest] draft - Save writes the per-quest JSON
 * via [com.projectx.quest.data.QuestLibrary.saveQuest] and reloads the library.
 */
object QuestEditorWindow {
    @JvmStatic
    @ImGUIRender(priority = Priority.LOW)
    fun render() {
        try {
            if (!QuestEditorState.windowOpen.value) return
            val draft = QuestEditorState.draft ?: run {
                QuestEditorState.windowOpen.value = false
                return
            }

            // Apply any entity/tile pick captured since the last frame (render thread).
            QuestEditorState.drainPickDelivery()

            setNextWindowPos(540f, 10f, ImGuiCond.FirstUseEver)
            setNextWindowSize(720f, 860f, ImGuiCond.FirstUseEver)

            window("Quest Editor - ${draft.name}", WindowFlags.None, open = QuestEditorState.windowOpen) {
                renderToolbar(draft)
                separator()
                renderDialogDebug(draft)
                separator()
                renderMeta(draft)
                separator()
                renderStepsPane(draft)
            }
        } catch (t: Throwable) {
            println("[QuestEditorWindow] render error: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun WindowScope.renderToolbar(draft: MutableQuest) {
        smallButton("Save") { QuestEditorState.save() }
        sameLine()
        smallButton("Reload from disk") {
            val slug = draft.slug
            val editorStep = QuestEditorState.selectedStepIndex.value
            QuestLibrary.reload()
            QuestLibrary.bySlug(slug)?.let {
                QuestEditorState.beginEdit(it)
                QuestEditorState.selectedStepIndex.value =
                    editorStep.coerceIn(0, (it.steps.size - 1).coerceAtLeast(0))
            }
        }
        sameLine()
        smallButton("Discard") { QuestEditorState.discard() }
        sameLine()
        smallButton("Split all to files") {
            val n = QuestLibrary.splitBundleToFiles(overwrite = false)
            QuestEditorState.saveStatus.value = "Split $n quests to per-quest files"
        }
        val status = QuestEditorState.saveStatus.value
        if (status.isNotEmpty()) {
            sameLine()
            text("  $status")
        }
    }

    private fun WindowScope.renderDialogDebug(draft: MutableQuest) {
        val enabled = ConditionEvaluator.debugCaptureEnabled
        checkbox("Dialog capture debug", enabled) { ConditionEvaluator.debugCaptureEnabled = it }
        sameLine()
        smallButton("Log dialog state") { ConditionEvaluator.logDialogState() }
        sameLine()
        smallButton("Sweep 0..2000") { ConditionEvaluator.sweepAllInterfaces() }
        sameLine()
        text("  (auto-logs misses when debug is on; buttons print to projectx log)")
        if (!enabled) return
        val captures = ConditionEvaluator.captureAllDialogText()
        if (captures.isEmpty()) {
            text("  (no open dialog interfaces produced any text)")
            return
        }
        child("dialog-debug", height = 140f) {
            for (cap in captures) {
                text("[${cap.interfaceId}] ${cap.sample}")
                sameLine()
                smallButton("+ Dialog says##dlg${cap.interfaceId}") {
                    val step = draft.steps.getOrNull(QuestEditorState.selectedStepIndex.value) ?: return@smallButton
                    val snippet = cap.sample.take(60).trim()
                    if (snippet.isNotEmpty()) {
                        step.postconditions += QuestCondition.ConversationText(snippet)
                    }
                }
            }
        }
    }

    private fun WindowScope.renderMeta(draft: MutableQuest) {
        child("editor-meta", height = 110f) {
            pushItemWidth(-180f)
            inputText("Slug", draft.slug, maxLength = 96) { draft.slug = it.trim() }
            inputText("Display name", draft.name, maxLength = 96) { draft.name = it }
            inputText("Length tag", draft.length ?: "", maxLength = 32) { draft.length = it.ifBlank { null } }
            popItemWidth()
            checkbox("Members", draft.members) { draft.members = it }
            sameLine()
            setNextItemWidth(NUM_W)
            inputInt("Stage varbit", draft.stageVarbit) { draft.stageVarbit = it }
            sameLine()
            setNextItemWidth(NUM_W)
            inputInt("Complete value", draft.stageVarbitCompleteValue) { draft.stageVarbitCompleteValue = it }
        }
    }

    private fun WindowScope.renderStepsPane(draft: MutableQuest) {
        renderStepHeader(draft)
        separator()
        val idx = QuestEditorState.selectedStepIndex.value.coerceIn(0, (draft.steps.size - 1).coerceAtLeast(0))
        QuestEditorState.selectedStepIndex.value = idx
        if (draft.steps.isEmpty()) {
            text("No steps yet. Click 'Add step' to begin.")
            return
        }
        child("editor-step-list", width = 200f, height = 0f) {
            renderStepList(draft, idx)
        }
        sameLine()
        val step = draft.steps[idx]
        child("editor-step", width = 0f, height = 0f) {
            renderStepEditor(draft, idx, step)
        }
    }

    private fun ChildScope.renderStepList(draft: MutableQuest, currentIdx: Int) {
        val liveIdx = ActiveQuestState.currentStepIndex
        for ((i, step) in draft.steps.withIndex()) {
            val marker = when {
                i == liveIdx -> "* "
                i == currentIdx -> "> "
                else -> "  "
            }
            val label = step.title?.takeIf { it.isNotBlank() }
                ?: step.text?.take(28)?.replace(Regex("<[^>]+>"), "")
                ?: "(step ${i + 1})"
            selectable("$marker${i + 1}. $label##stepSel$i", i == currentIdx) {
                QuestEditorState.selectedStepIndex.value = i
            }
        }
    }

    private fun WindowScope.renderStepHeader(draft: MutableQuest) {
        val idx = QuestEditorState.selectedStepIndex.value
        text("Step ${if (draft.steps.isEmpty()) 0 else idx + 1} / ${draft.steps.size}")
        sameLine()
        arrowButton("stepPrev", ImGuiDir.Left, "Prev") {
            if (idx > 0) QuestEditorState.selectedStepIndex.value = idx - 1
        }
        sameLine()
        arrowButton("stepNext", ImGuiDir.Right, "Next") {
            if (idx < draft.steps.size - 1) QuestEditorState.selectedStepIndex.value = idx + 1
        }
        sameLine()
        smallButton("Add step") {
            draft.steps.add(idx + 1, MutableStep(title = "New step"))
            QuestEditorState.selectedStepIndex.value = idx + 1
        }
        sameLine()
        smallButton("Duplicate") {
            if (draft.steps.isNotEmpty()) {
                val src = draft.steps[idx]
                draft.steps.add(idx + 1, MutableStep.fromStep(src.toStep()))
                QuestEditorState.selectedStepIndex.value = idx + 1
            }
        }
        sameLine()
        arrowButton("moveStepUp", ImGuiDir.Up, "Move up") {
            if (idx > 0) {
                val tmp = draft.steps[idx]
                draft.steps[idx] = draft.steps[idx - 1]
                draft.steps[idx - 1] = tmp
                QuestEditorState.selectedStepIndex.value = idx - 1
            }
        }
        sameLine()
        arrowButton("moveStepDown", ImGuiDir.Down, "Move down") {
            if (idx < draft.steps.size - 1) {
                val tmp = draft.steps[idx]
                draft.steps[idx] = draft.steps[idx + 1]
                draft.steps[idx + 1] = tmp
                QuestEditorState.selectedStepIndex.value = idx + 1
            }
        }
        sameLine()
        smallButton("Delete##stepDel") {
            if (draft.steps.isNotEmpty()) {
                draft.steps.removeAt(idx)
                if (QuestEditorState.selectedStepIndex.value >= draft.steps.size) {
                    QuestEditorState.selectedStepIndex.value = (draft.steps.size - 1).coerceAtLeast(0)
                }
            }
        }
        sameLine()
        smallButton("Jump to live") {
            QuestEditorState.selectedStepIndex.value = ActiveQuestState.currentStepIndex
        }
    }

    private fun ChildScope.renderStepEditor(draft: MutableQuest, idx: Int, step: MutableStep) {
        pushItemWidth(-130f)
        inputText("Title##stepTitle", step.title ?: "", maxLength = 256) { step.title = it.ifBlank { null } }
        inputTextMultiline("Text##stepText", step.text ?: "", maxLength = 4096, sizeY = 70f) { step.text = it.ifBlank { null } }
        inputText("Warning##stepWarn", step.warning ?: "", maxLength = 256) { step.warning = it.ifBlank { null } }
        popItemWidth()

        val solverIds = listOf("(none)") + QuestSolverRegistry.ids()
        val currentSolverIdx = if (step.solverId.isNullOrEmpty()) 0
            else solverIds.indexOf(step.solverId).coerceAtLeast(0)
        setNextItemWidth(200f)
        combo("Solver##stepSolver", currentSolverIdx, solverIds) { selected ->
            step.solverId = if (selected <= 0) null else solverIds[selected]
        }
        sameLine()
        text("(${QuestSolverRegistry.ids().size} registered)")

        separator()
        renderActionsList(step)
        separator()
        renderConditionsList(
            label = "Postconditions (advance when all met)",
            target = step.postconditions,
            keyPrefix = "post$idx",
        )
        separator()
        renderJumpconditions(step, idx)
        separator()
        renderStepItems(step, idx)
    }

    private fun ChildScope.renderActionsList(step: MutableStep) {
        text("Actions (overlay + dialog/inventory highlights)")
        sameLine()
        renderAddActionMenu(step)
        if (step.actions.isEmpty()) {
            text("  (no actions)")
            return
        }
        for ((i, action) in step.actions.withIndex()) {
            pushItemWidth(0f)
            renderActionEditor(step, i, action)
            popItemWidth()
            separator()
        }
    }

    private fun ChildScope.renderAddActionMenu(step: MutableStep) {
        val options = listOf("(add action...)", "+ Direction", "+ Model highlight", "+ Conversation", "+ Continue", "+ Inventory highlight", "+ Path guide", "+ Reset instance", "+ Text hint", "+ Component highlight")
        combo("##addAction", 0, options) { sel ->
            when (sel) {
                1 -> step.actions += QuestActionFactory.direction(0, 0)
                2 -> step.actions += QuestActionFactory.modelHighlight()
                3 -> step.actions += QuestActionFactory.conversationHighlight()
                4 -> step.actions += QuestActionFactory.continueConversation()
                5 -> step.actions += QuestActionFactory.inventoryHighlight()
                6 -> step.actions += QuestActionFactory.pathGuide()
                7 -> step.actions += QuestActionFactory.resetInstance()
                8 -> step.actions += QuestActionFactory.textHint("")
                9 -> step.actions += QuestActionFactory.interfaceComponentHighlight()
            }
        }
    }

    private fun ChildScope.renderActionEditor(step: MutableStep, idx: Int, action: QuestAction) {
        val keyId = "act$idx"
        text("Action ${idx + 1}: ${actionLabel(action)}")
        sameLine()
        smallButton("x##${keyId}del") { step.actions.removeAt(idx) }
        sameLine()
        arrowButton("${keyId}up", ImGuiDir.Up, "Up") { if (idx > 0) { val t = step.actions[idx]; step.actions[idx] = step.actions[idx - 1]; step.actions[idx - 1] = t } }
        sameLine()
        arrowButton("${keyId}dn", ImGuiDir.Down, "Down") { if (idx < step.actions.size - 1) { val t = step.actions[idx]; step.actions[idx] = step.actions[idx + 1]; step.actions[idx + 1] = t } }
        when (action) {
            is QuestAction.Direction -> renderDirectionEditor(step, idx, action, keyId)
            is QuestAction.ModelHighlight -> renderModelHighlightEditor(step, idx, action, keyId)
            is QuestAction.ConversationHighlight -> {
                pushItemWidth(-80f)
                inputText("text##$keyId", action.text, maxLength = 256) { step.actions[idx] = action.copy(text = it) }
                popItemWidth()
            }
            is QuestAction.InventoryHighlight -> renderInventoryHighlightEditor(step, idx, action, keyId)
            is QuestAction.InterfaceComponentHighlight -> {
                setNextItemWidth(NUM_W)
                inputInt("interfaceId##$keyId", action.interfaceId) { step.actions[idx] = action.copy(interfaceId = it) }
                sameLine()
                setNextItemWidth(NUM_W)
                inputInt("componentId##$keyId", action.componentId) { step.actions[idx] = action.copy(componentId = it) }
                setNextItemWidth(NUM_W)
                inputInt("slot (-1 = whole component)##$keyId", action.slotId) { step.actions[idx] = action.copy(slotId = it) }
                pushItemWidth(-80f)
                inputText("label##$keyId", action.label, maxLength = 96) { step.actions[idx] = action.copy(label = it) }
                popItemWidth()
                renderComponentPickRow(keyId) { r ->
                    val cur = (step.actions.getOrNull(idx) as? QuestAction.InterfaceComponentHighlight) ?: return@renderComponentPickRow
                    step.actions[idx] = cur.copy(interfaceId = r.interfaceId, componentId = r.componentId, slotId = r.slotId)
                }
            }
            QuestAction.ContinueConversation -> text("  -> Press 'Continue' in dialog")
            QuestAction.ResetInstance -> text("  -> Reset the instance")
            is QuestAction.PathGuide -> renderPathGuideEditor(step, idx, action, keyId)
            is QuestAction.TextHint -> {
                pushItemWidth(-80f)
                inputText("text##$keyId", action.text, maxLength = 256) { step.actions[idx] = action.copy(text = it) }
                popItemWidth()
            }
            is QuestAction.Unknown -> text("  unsupported: ${action.name}")
        }
    }

    private fun ChildScope.renderDirectionEditor(step: MutableStep, idx: Int, action: QuestAction.Direction, keyId: String) {
        xyzRow(keyId, action.x.toInt(), action.y.toInt(), action.heightFine.toInt()) { nx, ny, nz ->
            step.actions[idx] = action.copy(x = nx.toDouble(), y = ny.toDouble(), heightFine = nz.toDouble())
        }
        checkbox("instance##$keyId", action.instance) { step.actions[idx] = action.copy(instance = it) }
        sameLine()
        checkbox("snap to tile##$keyId", action.tile) { step.actions[idx] = action.copy(tile = it) }
        sameLine()
        setNextItemWidth(NUM_W)
        inputInt("auto-adv dist##$keyId", action.distance) { step.actions[idx] = action.copy(distance = it.coerceAtLeast(-1)) }
        renderTilePickRow(keyId) { hit ->
            step.actions[idx] = action.copy(x = hit.tileX.toDouble(), y = hit.tileY.toDouble(), heightFine = hit.heightFine.toDouble(), instance = hit.instance)
        }
    }

    private fun ChildScope.renderModelHighlightEditor(step: MutableStep, idx: Int, action: QuestAction.ModelHighlight, keyId: String) {
        val kinds = listOf("npc", "object", "item", "grounditem", "model")
        setNextItemWidth(110f)
        combo("kind##$keyId", kinds.indexOf(action.kind).coerceAtLeast(0), kinds) {
            step.actions[idx] = action.copy(kind = kinds[it])
        }
        sameLine()
        setNextItemWidth(NUM_W)
        inputInt("typeId##$keyId", action.typeId) { step.actions[idx] = action.copy(typeId = it) }
        pushItemWidth(-130f)
        inputText("name##$keyId", action.displayName, maxLength = 128) { step.actions[idx] = action.copy(displayName = it) }
        inputText("modelIds csv##$keyId", action.modelIds.joinToString(","), maxLength = 256) {
            step.actions[idx] = action.copy(modelIds = parseCsvInts(it))
        }
        popItemWidth()
        renderEntityPickRow(keyId) { r ->
            val cur = (step.actions.getOrNull(idx) as? QuestAction.ModelHighlight) ?: return@renderEntityPickRow
            step.actions[idx] = cur.copy(kind = kindString(r.kind), typeId = r.typeId, displayName = r.name)
        }
        val priorityOpts = listOf("(closest)", "all")
        val pIdx = if (action.priority == "all") 1 else 0
        setNextItemWidth(110f)
        combo("priority##$keyId", pIdx, priorityOpts) {
            step.actions[idx] = action.copy(priority = if (it == 1) "all" else null)
        }
        sameLine()
        checkbox("instance##mh$keyId", action.instance) { step.actions[idx] = action.copy(instance = it) }
        sameLine()
        setNextItemWidth(NUM_W)
        inputInt("auto-adv dist##mh$keyId", action.distance) { step.actions[idx] = action.copy(distance = it.coerceAtLeast(-1)) }

        val locOrNull = action.atLocation
        val hasLoc = locOrNull != null
        checkbox("anchor to location##mh$keyId", hasLoc) {
            step.actions[idx] = action.copy(atLocation = if (it) WorldLocation(0.0, 0.0, 0.0) else null)
        }
        if (locOrNull != null) {
            val loc = locOrNull
            xyzRow("mhLoc$keyId", loc.x.toInt(), loc.y.toInt(), loc.heightFine.toInt()) { nx, ny, nz ->
                step.actions[idx] = action.copy(atLocation = WorldLocation(nx.toDouble(), nz.toDouble(), ny.toDouble()))
            }
            renderTilePickRow("mhLoc$keyId") { hit ->
                step.actions[idx] = action.copy(atLocation = WorldLocation(hit.tileX.toDouble(), hit.heightFine.toDouble(), hit.tileY.toDouble()), instance = hit.instance)
            }
        }
    }

    private fun ChildScope.renderInventoryHighlightEditor(step: MutableStep, idx: Int, action: QuestAction.InventoryHighlight, keyId: String) {
        setNextItemWidth(NUM_W)
        inputInt("itemId##$keyId", action.itemId) { step.actions[idx] = action.copy(itemId = it) }
        pushItemWidth(-130f)
        inputText("name##$keyId", action.displayName, maxLength = 128) { step.actions[idx] = action.copy(displayName = it) }
        inputText("modelIds csv##$keyId", action.modelIds.joinToString(","), maxLength = 256) {
            step.actions[idx] = action.copy(modelIds = parseCsvInts(it))
        }
        popItemWidth()
        renderItemPickRow(keyId) { r ->
            val cur = (step.actions.getOrNull(idx) as? QuestAction.InventoryHighlight) ?: return@renderItemPickRow
            step.actions[idx] = cur.copy(itemId = r.typeId, displayName = r.name)
        }
    }

    private fun ChildScope.renderPathGuideEditor(step: MutableStep, idx: Int, action: QuestAction.PathGuide, keyId: String) {
        checkbox("instance##$keyId", action.instance) { step.actions[idx] = action.copy(instance = it) }
        sameLine()
        smallButton("+ waypoint at player") {
            val hit = currentPlayerTilePick()?.let(::toInstanceAware) ?: return@smallButton
            val pts = action.waypoints + QuestAction.PathGuide.Waypoint(hit.tileX.toDouble(), hit.heightFine.toDouble(), hit.tileY.toDouble())
            step.actions[idx] = action.copy(waypoints = pts, instance = hit.instance)
        }
        sameLine()
        smallButton("+ waypoint by pick") {
            QuestEditorState.beginPickTile { tx, ty, pl, hz ->
                val hit = toInstanceAware(TilePickResolver.Hit(tx, ty, pl, hz, 0f, 0f))
                val current = (step.actions.getOrNull(idx) as? QuestAction.PathGuide) ?: action
                val pts = current.waypoints + QuestAction.PathGuide.Waypoint(hit.tileX.toDouble(), hit.heightFine.toDouble(), hit.tileY.toDouble())
                step.actions[idx] = current.copy(waypoints = pts, instance = hit.instance)
            }
        }
        for ((wi, wp) in action.waypoints.withIndex()) {
            text("  - (${wp.x.toInt()}, ${wp.y.toInt()})  fineZ=${wp.heightFine.toInt()}")
            sameLine()
            smallButton("x##$keyId-w$wi") {
                step.actions[idx] = action.copy(waypoints = action.waypoints.toMutableList().apply { removeAt(wi) })
            }
        }
    }

    private fun ChildScope.renderConditionsList(label: String, target: MutableList<QuestCondition>, keyPrefix: String) {
        text(label)
        sameLine()
        renderAddConditionMenu(target, keyPrefix)
        if (target.isEmpty()) {
            text("  (none)")
            return
        }
        for ((i, c) in target.withIndex()) {
            renderConditionEditor(target, i, c, "$keyPrefix-c$i")
            separator()
        }
    }

    private fun ChildScope.renderAddConditionMenu(target: MutableList<QuestCondition>, keyPrefix: String) {
        // Index 0 is an unselectable placeholder: the combo starts at currentIndex 0 and
        // only fires onChange when the picked index differs, so a real option at 0 is unreachable.
        val options = listOf(
            "(add condition...)",
            "+ Distance to", "+ Distance from", "+ Inventory contains", "+ Inventory does NOT contain",
            "+ Model visible", "+ Model not visible", "+ Conversation text", "+ Chat text",
            "+ Conversation active", "+ Conversation inactive", "+ In instance", "+ Not in instance",
            "+ Always", "+ Manual", "+ Quest started", "+ Quest complete", "+ Interface open", "+ NPC near tile",
        )
        combo("##add$keyPrefix", 0, options) { sel ->
            when (sel) {
                1 -> target += QuestConditionFactory.distanceTo(0, 0)
                2 -> target += QuestConditionFactory.distanceFrom(0, 0)
                3 -> target += QuestConditionFactory.inventoryContains()
                4 -> target += QuestConditionFactory.inventoryDoesNotContain()
                5 -> target += QuestConditionFactory.modelVisible()
                6 -> target += QuestConditionFactory.modelNotVisible()
                7 -> target += QuestConditionFactory.conversationText()
                8 -> target += QuestConditionFactory.chatText()
                9 -> target += QuestCondition.ConversationActive
                10 -> target += QuestCondition.ConversationInactive
                11 -> target += QuestCondition.InInstance
                12 -> target += QuestCondition.NotInInstance
                13 -> target += QuestCondition.Always
                14 -> target += QuestCondition.Manual
                15 -> target += QuestCondition.QuestStarted
                16 -> target += QuestCondition.QuestComplete
                17 -> target += QuestCondition.InterfaceOpen()
                18 -> target += QuestCondition.NpcNearTile()
            }
        }
    }

    private fun ChildScope.renderConditionEditor(target: MutableList<QuestCondition>, idx: Int, c: QuestCondition, keyId: String) {
        text("- ${conditionLabel(c)}")
        sameLine()
        smallButton("x##${keyId}del") { target.removeAt(idx) }
        sameLine()
        arrowButton("${keyId}up", ImGuiDir.Up, "Up") { if (idx > 0) { val t = target[idx]; target[idx] = target[idx - 1]; target[idx - 1] = t } }
        sameLine()
        arrowButton("${keyId}dn", ImGuiDir.Down, "Down") { if (idx < target.size - 1) { val t = target[idx]; target[idx] = target[idx + 1]; target[idx + 1] = t } }
        when (c) {
            is QuestCondition.DistanceTo -> renderDistanceEditor(target, idx, c, keyId, isFrom = false, withHeight = false)
            is QuestCondition.DistanceFrom -> renderDistanceEditor(target, idx, c, keyId, isFrom = true, withHeight = false)
            is QuestCondition.DistanceToWithHeight -> renderDistanceEditor(target, idx, c, keyId, isFrom = false, withHeight = true)
            is QuestCondition.DistanceFromWithHeight -> renderDistanceEditor(target, idx, c, keyId, isFrom = true, withHeight = true)
            is QuestCondition.InventoryContains -> renderInvContainsEditor(target, idx, c, keyId)
            is QuestCondition.InventoryDoesNotContain -> renderInvDoesNotContainEditor(target, idx, c, keyId)
            is QuestCondition.ModelVisible -> renderModelVisibilityEditor(target, idx, c, keyId)
            is QuestCondition.ModelNotVisible -> renderModelNotVisibleEditor(target, idx, c, keyId)
            is QuestCondition.ConversationText -> {
                pushItemWidth(-80f)
                inputText("text##$keyId", c.text, maxLength = 256) { target[idx] = c.copy(text = it) }
                popItemWidth()
            }
            is QuestCondition.ChatText -> {
                pushItemWidth(-80f)
                inputText("text##$keyId", c.text, maxLength = 256) { target[idx] = c.copy(text = it) }
                popItemWidth()
            }
            is QuestCondition.CaptureConversationState -> {
                pushItemWidth(-100f)
                inputText("pattern##$keyId", c.pattern, maxLength = 256) { target[idx] = c.copy(pattern = it) }
                inputText("key##$keyId", c.key, maxLength = 96) { target[idx] = c.copy(key = it) }
                popItemWidth()
            }
            is QuestCondition.StateEquals -> {
                pushItemWidth(-100f)
                inputText("key##$keyId", c.key, maxLength = 96) { target[idx] = c.copy(key = it) }
                inputText("value##$keyId", c.value, maxLength = 96) { target[idx] = c.copy(value = it) }
                popItemWidth()
            }
            is QuestCondition.InCombatWith -> {
                setNextItemWidth(NUM_W)
                inputInt("npcId##$keyId", c.npcId) { target[idx] = c.copy(npcId = it) }
                pushItemWidth(-130f)
                inputText("name##$keyId", c.displayName, maxLength = 96) { target[idx] = c.copy(displayName = it) }
                popItemWidth()
            }
            is QuestCondition.ItemClicked -> {
                setNextItemWidth(NUM_W)
                inputInt("itemId##$keyId", c.itemId) { target[idx] = c.copy(itemId = it) }
            }
            is QuestCondition.InterfaceOpen -> {
                setNextItemWidth(NUM_W)
                inputInt("interfaceId##$keyId", c.interfaceId) { target[idx] = c.copy(interfaceId = it) }
            }
            is QuestCondition.NpcNearTile -> renderNpcNearTileEditor(target, idx, c, keyId)
            QuestCondition.Always, QuestCondition.Manual, QuestCondition.NotInInstance, QuestCondition.InInstance,
            QuestCondition.ChangedInstance, QuestCondition.Generic, QuestCondition.QuestStarted,
            QuestCondition.QuestComplete, QuestCondition.QuestInterfaceOpen,
            QuestCondition.ConversationActive, QuestCondition.ConversationInactive -> Unit
            is QuestCondition.Unknown -> text("  unsupported: ${c.name}")
        }
    }

    private fun ChildScope.renderDistanceEditor(
        target: MutableList<QuestCondition>,
        idx: Int,
        c: QuestCondition,
        keyId: String,
        isFrom: Boolean,
        withHeight: Boolean,
    ) {
        val (x, h, y, range, inst) = unpackDistance(c)
        xyzRow(keyId, x.toInt(), y.toInt(), h.toInt()) { nx, ny, nz ->
            target[idx] = repackDistance(c, nx.toDouble(), nz.toDouble(), ny.toDouble(), range, inst)
        }
        setNextItemWidth(NUM_W)
        inputInt("rng##$keyId", range) { target[idx] = repackDistance(c, x, h, y, it.coerceAtLeast(0), inst) }
        sameLine()
        checkbox("instance##$keyId", inst) { target[idx] = repackDistance(c, x, h, y, range, it) }
        renderTilePickRow(keyId) { hit ->
            target[idx] = repackDistance(c, hit.tileX.toDouble(), hit.heightFine.toDouble(), hit.tileY.toDouble(), range, hit.instance)
        }
    }

    private fun ChildScope.renderInvContainsEditor(target: MutableList<QuestCondition>, idx: Int, c: QuestCondition.InventoryContains, keyId: String) {
        setNextItemWidth(NUM_W)
        inputInt("itemId##$keyId", c.itemId) { target[idx] = c.copy(itemId = it) }
        sameLine()
        setNextItemWidth(NUM_W_SMALL)
        inputInt("qty##$keyId", c.quantity) { target[idx] = c.copy(quantity = it.coerceAtLeast(1)) }
        pushItemWidth(-130f)
        inputText("name##$keyId", c.displayName, maxLength = 96) { target[idx] = c.copy(displayName = it) }
        popItemWidth()
        renderItemPickRow(keyId) { r ->
            val cur = (target.getOrNull(idx) as? QuestCondition.InventoryContains) ?: return@renderItemPickRow
            target[idx] = cur.copy(itemId = r.typeId, displayName = r.name)
        }
    }

    private fun ChildScope.renderInvDoesNotContainEditor(target: MutableList<QuestCondition>, idx: Int, c: QuestCondition.InventoryDoesNotContain, keyId: String) {
        setNextItemWidth(NUM_W)
        inputInt("itemId##$keyId", c.itemId) { target[idx] = c.copy(itemId = it) }
        sameLine()
        pushItemWidth(-130f)
        inputText("name##$keyId", c.displayName, maxLength = 96) { target[idx] = c.copy(displayName = it) }
        popItemWidth()
        renderItemPickRow(keyId) { r ->
            val cur = (target.getOrNull(idx) as? QuestCondition.InventoryDoesNotContain) ?: return@renderItemPickRow
            target[idx] = cur.copy(itemId = r.typeId, displayName = r.name)
        }
    }

    private fun ChildScope.renderModelVisibilityEditor(target: MutableList<QuestCondition>, idx: Int, c: QuestCondition.ModelVisible, keyId: String) {
        val kinds = listOf("npc", "object", "item", "grounditem", "model")
        setNextItemWidth(110f)
        combo("kind##$keyId", kinds.indexOf(c.kind).coerceAtLeast(0), kinds) { target[idx] = c.copy(kind = kinds[it]) }
        sameLine()
        setNextItemWidth(NUM_W)
        inputInt("typeId##$keyId", c.typeId) { target[idx] = c.copy(typeId = it) }
        sameLine()
        setNextItemWidth(NUM_W_SMALL)
        inputInt("qty##$keyId", c.quantity) { target[idx] = c.copy(quantity = it) }
        pushItemWidth(-130f)
        inputText("name##$keyId", c.displayName, maxLength = 96) { target[idx] = c.copy(displayName = it) }
        popItemWidth()
        renderEntityPickRow(keyId) { r ->
            val cur = (target.getOrNull(idx) as? QuestCondition.ModelVisible) ?: return@renderEntityPickRow
            target[idx] = cur.copy(kind = kindString(r.kind), typeId = r.typeId, displayName = r.name)
        }
        checkbox("animated##$keyId", c.animated) { target[idx] = c.copy(animated = it) }
        sameLine()
        checkbox("instance##$keyId", c.instance) { target[idx] = c.copy(instance = it) }
    }

    private fun ChildScope.renderNpcNearTileEditor(target: MutableList<QuestCondition>, idx: Int, c: QuestCondition.NpcNearTile, keyId: String) {
        setNextItemWidth(NUM_W)
        inputInt("npc typeId##$keyId", c.typeId) { target[idx] = c.copy(typeId = it) }
        sameLine()
        pushItemWidth(-130f)
        inputText("npc name##$keyId", c.displayName, maxLength = 96) { target[idx] = c.copy(displayName = it) }
        popItemWidth()
        renderEntityPickRow(keyId, setOf(PickKind.NPC), "examine an NPC", "Pick NPC") { r ->
            val cur = (target.getOrNull(idx) as? QuestCondition.NpcNearTile) ?: return@renderEntityPickRow
            target[idx] = cur.copy(typeId = r.typeId, displayName = r.name)
        }
        setNextItemWidth(NUM_W_SMALL)
        inputInt("x##$keyId", c.tileX) { target[idx] = c.copy(tileX = it) }
        sameLine()
        setNextItemWidth(NUM_W_SMALL)
        inputInt("y##$keyId", c.tileY) { target[idx] = c.copy(tileY = it) }
        sameLine()
        setNextItemWidth(NUM_W_SMALL)
        inputInt("plane##$keyId", c.plane) { target[idx] = c.copy(plane = it) }
        renderTilePickRow("nnt$keyId") { hit ->
            val cur = (target.getOrNull(idx) as? QuestCondition.NpcNearTile) ?: return@renderTilePickRow
            target[idx] = cur.copy(tileX = hit.tileX, tileY = hit.tileY, plane = hit.plane, instance = hit.instance)
        }
        checkbox("instance (local coords)##$keyId", c.instance) { target[idx] = c.copy(instance = it) }
        sameLine()
        setNextItemWidth(NUM_W)
        inputInt("distance##$keyId", c.distance) { target[idx] = c.copy(distance = it.coerceAtLeast(0)) }
    }

    private fun ChildScope.renderModelNotVisibleEditor(target: MutableList<QuestCondition>, idx: Int, c: QuestCondition.ModelNotVisible, keyId: String) {
        val kinds = listOf("npc", "object", "item", "grounditem", "model")
        setNextItemWidth(110f)
        combo("kind##$keyId", kinds.indexOf(c.kind).coerceAtLeast(0), kinds) { target[idx] = c.copy(kind = kinds[it]) }
        sameLine()
        setNextItemWidth(NUM_W)
        inputInt("typeId##$keyId", c.typeId) { target[idx] = c.copy(typeId = it) }
        sameLine()
        checkbox("instance##$keyId", c.instance) { target[idx] = c.copy(instance = it) }
        pushItemWidth(-130f)
        inputText("name##$keyId", c.displayName, maxLength = 96) { target[idx] = c.copy(displayName = it) }
        popItemWidth()
        renderEntityPickRow(keyId) { r ->
            val cur = (target.getOrNull(idx) as? QuestCondition.ModelNotVisible) ?: return@renderEntityPickRow
            target[idx] = cur.copy(kind = kindString(r.kind), typeId = r.typeId, displayName = r.name)
        }
    }

    private fun ChildScope.renderJumpconditions(step: MutableStep, idx: Int) {
        text("Jumpconditions (jump by offset if all met)")
        setNextItemWidth(NUM_W)
        inputInt("jumpOffset##jump$idx", step.jumpOffset) { step.jumpOffset = it }
        renderConditionsList(
            label = "Triggers:",
            target = step.jumpconditions,
            keyPrefix = "jump$idx",
        )
    }

    private fun ChildScope.renderStepItems(step: MutableStep, stepIdx: Int) {
        text("Items needed during step")
        renderItemReqList(step.neededItems, "needed$stepIdx")
        text("Items recommended during step")
        renderItemReqList(step.recommendedItems, "rec$stepIdx")
    }

    private fun ChildScope.renderItemReqList(items: MutableList<QuestItemReq>, keyPrefix: String) {
        for ((i, req) in items.withIndex()) {
            setNextItemWidth(NUM_W)
            inputInt("id##$keyPrefix-id$i", req.itemId) { items[i] = req.copy(itemId = it) }
            sameLine()
            setNextItemWidth(NUM_W_SMALL)
            inputInt("qty##$keyPrefix-q$i", req.quantity) { items[i] = req.copy(quantity = it.coerceAtLeast(1)) }
            sameLine()
            setNextItemWidth(-80f)
            inputText("name##$keyPrefix-n$i", req.name, maxLength = 96) { items[i] = req.copy(name = it) }
            sameLine()
            smallButton("x##$keyPrefix-x$i") { items.removeAt(i) }
            // Pick an item in-game to fill this req's id + name (re-fetch by index: the pick
            // callback fires later, mirroring renderInventoryHighlightEditor).
            renderItemPickRow("$keyPrefix-pick$i") { r ->
                val cur = items.getOrNull(i) ?: return@renderItemPickRow
                items[i] = cur.copy(itemId = r.typeId, name = r.name)
            }
        }
        smallButton("+ item##$keyPrefix") { items += QuestItemReq(name = "", itemId = -1, quantity = 1, duringQuest = true) }
    }

    /**
     * The single tile picker every tile-taking condition/action uses. Auto-detects
     * instances: when the player is inside one, the delivered [TilePickResolver.Hit]
     * carries instance-local coords (delta from the captured origin) and
     * [TilePickResolver.Hit.instance] = true. Callers store the coords + that flag
     * verbatim; eval translates back via the instance origin.
     */
    private fun ChildScope.renderTilePickRow(keyId: String, onPicked: (TilePickResolver.Hit) -> Unit) {
        smallButton("Use player tile##$keyId") {
            currentPlayerTilePick()?.let { onPicked(toInstanceAware(it)) }
        }
        sameLine()
        val active = QuestEditorState.pickMode.value
        smallButton((if (active) "Cancel pick##$keyId" else "Pick tile##$keyId")) {
            if (active) {
                QuestEditorState.cancelPick()
            } else {
                QuestEditorState.beginPickTile { tx, ty, pl, hz ->
                    onPicked(toInstanceAware(TilePickResolver.Hit(tx, ty, pl, hz, 0f, 0f)))
                }
            }
        }
        if (active) {
            sameLine()
            text(if (QuestInstanceTracker.origin != null) "  + click world (auto: instance-local)" else "  + click world to capture")
        }
    }

    /** Converts an absolute picked tile to instance-local coords (flagging it) when the player is in an instance. */
    private fun toInstanceAware(hit: TilePickResolver.Hit): TilePickResolver.Hit {
        val origin = QuestInstanceTracker.origin ?: return hit.copy(instance = false)
        return hit.copy(tileX = hit.tileX - origin.x, tileY = hit.tileY - origin.y, instance = true)
    }

    /** "Pick" row: captures the next matching entity the user interacts with in-game. */
    private fun ChildScope.renderEntityPickRow(
        keyId: String,
        accepts: Set<PickKind> = setOf(PickKind.OBJECT, PickKind.NPC, PickKind.ITEM, PickKind.GROUND_ITEM),
        prompt: String = "examine an object / npc / item / ground item",
        label: String = "Pick (examine)",
        onPicked: (PickResult) -> Unit,
    ) {
        val active = QuestEditorState.pickMode.value
        smallButton(if (active) "Cancel pick##ep$keyId" else "$label##ep$keyId") {
            if (active) QuestEditorState.cancelPick()
            else QuestEditorState.beginPick(accepts, prompt) { onPicked(it) }
        }
        if (active) { sameLine(); text("  + ${QuestEditorState.pickPrompt.value}") }
    }

    /** "Pick item" row: captures the next item the user clicks/examines in-game. */
    private fun ChildScope.renderItemPickRow(keyId: String, onPicked: (PickResult) -> Unit) {
        val active = QuestEditorState.pickMode.value
        smallButton(if (active) "Cancel pick##ip$keyId" else "Pick item##ip$keyId") {
            if (active) QuestEditorState.cancelPick()
            else QuestEditorState.beginPick(setOf(PickKind.ITEM), "click or examine an item") { onPicked(it) }
        }
        if (active) { sameLine(); text("  + ${QuestEditorState.pickPrompt.value}") }
    }

    /** "Pick component" row: captures the next interface component the user clicks in-game (slot included). */
    private fun ChildScope.renderComponentPickRow(keyId: String, onPicked: (PickResult) -> Unit) {
        val active = QuestEditorState.pickMode.value
        smallButton(if (active) "Cancel pick##cp$keyId" else "Pick component##cp$keyId") {
            if (active) QuestEditorState.cancelPick()
            else QuestEditorState.beginPick(setOf(PickKind.COMPONENT), "click the interface component to capture") { onPicked(it) }
        }
        if (active) { sameLine(); text("  + ${QuestEditorState.pickPrompt.value}") }
    }

    private fun kindString(k: PickKind): String = when (k) {
        PickKind.NPC -> "npc"
        PickKind.OBJECT -> "object"
        PickKind.ITEM -> "item"
        PickKind.GROUND_ITEM -> "grounditem"
        PickKind.TILE -> "model"
        PickKind.COMPONENT -> "component"
    }

    /**
     * Compact world-coord row: three short-labelled int fields (x / y / z) at fixed
     * width. [z] is the heightFine value - short label so all three fit on one line.
     * Calls [onChange] with the full (x, y, z) tuple on every edit; the caller
     * decides how to remap (e.g. Direction stores heightFine in its own field).
     */
    private fun ChildScope.xyzRow(
        keyId: String,
        x: Int,
        y: Int,
        z: Int,
        onChange: (Int, Int, Int) -> Unit,
    ) {
        setNextItemWidth(NUM_W)
        inputInt("x##$keyId", x) { onChange(it, y, z) }
        sameLine()
        setNextItemWidth(NUM_W)
        inputInt("y##$keyId", y) { onChange(x, it, z) }
        sameLine()
        setNextItemWidth(NUM_W)
        inputInt("z##$keyId", z) { onChange(x, y, it) }
    }

    // setNextItemWidth on inputInt sets the WHOLE widget (input + step buttons + label
    // area). The ± buttons + spacing alone take ~55px; we need ~50px for digits + a few
    // more so labels don't overlap. 130px gives a comfortable input area.
    private const val NUM_W = 130f
    private const val NUM_W_SMALL = 100f

    private fun actionLabel(a: QuestAction): String = when (a) {
        is QuestAction.Direction -> "Direction"
        is QuestAction.ModelHighlight -> "ModelHighlight"
        is QuestAction.ConversationHighlight -> "ConversationHighlight"
        is QuestAction.InventoryHighlight -> "InventoryHighlight"
        is QuestAction.InterfaceComponentHighlight -> "InterfaceComponentHighlight"
        QuestAction.ContinueConversation -> "ContinueConversation"
        QuestAction.ResetInstance -> "ResetInstance"
        is QuestAction.PathGuide -> "PathGuide (${a.waypoints.size})"
        is QuestAction.TextHint -> "TextHint"
        is QuestAction.Unknown -> "Unknown(${a.name})"
    }

    private fun conditionLabel(c: QuestCondition): String = when (c) {
        is QuestCondition.DistanceTo -> "DistanceTo"
        is QuestCondition.DistanceFrom -> "DistanceFrom"
        is QuestCondition.DistanceToWithHeight -> "DistanceTo + Height"
        is QuestCondition.DistanceFromWithHeight -> "DistanceFrom + Height"
        is QuestCondition.InventoryContains -> "InventoryContains"
        is QuestCondition.InventoryDoesNotContain -> "InventoryDoesNotContain"
        is QuestCondition.ModelVisible -> "ModelVisible"
        is QuestCondition.ModelNotVisible -> "ModelNotVisible"
        is QuestCondition.ConversationText -> "ConversationText"
        is QuestCondition.ChatText -> "ChatText"
        is QuestCondition.CaptureConversationState -> "CaptureConversationState"
        is QuestCondition.StateEquals -> "StateEquals"
        is QuestCondition.InCombatWith -> "InCombatWith"
        is QuestCondition.ItemClicked -> "ItemClicked"
        QuestCondition.Always -> "Always"
        QuestCondition.Manual -> "Manual"
        QuestCondition.NotInInstance -> "NotInInstance"
        QuestCondition.InInstance -> "InInstance"
        QuestCondition.ChangedInstance -> "ChangedInstance"
        QuestCondition.Generic -> "Generic"
        QuestCondition.QuestStarted -> "QuestStarted"
        QuestCondition.QuestComplete -> "QuestComplete"
        QuestCondition.QuestInterfaceOpen -> "QuestInterfaceOpen"
        is QuestCondition.InterfaceOpen -> "InterfaceOpen"
        is QuestCondition.NpcNearTile -> "NpcNearTile"
        QuestCondition.ConversationActive -> "ConversationActive"
        QuestCondition.ConversationInactive -> "ConversationInactive"
        is QuestCondition.Unknown -> "Unknown(${c.name})"
    }

    private fun unpackDistance(c: QuestCondition): DistanceParts = when (c) {
        is QuestCondition.DistanceTo -> DistanceParts(c.x, c.heightFine, c.y, c.range, c.instance)
        is QuestCondition.DistanceFrom -> DistanceParts(c.x, c.heightFine, c.y, c.range, c.instance)
        is QuestCondition.DistanceToWithHeight -> DistanceParts(c.x, c.heightFine, c.y, c.range, c.instance)
        is QuestCondition.DistanceFromWithHeight -> DistanceParts(c.x, c.heightFine, c.y, c.range, c.instance)
        else -> DistanceParts(0.0, 0.0, 0.0, 0, false)
    }

    private fun repackDistance(orig: QuestCondition, x: Double, h: Double, y: Double, range: Int, inst: Boolean): QuestCondition = when (orig) {
        is QuestCondition.DistanceTo -> QuestCondition.DistanceTo(x, h, y, range, inst)
        is QuestCondition.DistanceFrom -> QuestCondition.DistanceFrom(x, h, y, range, inst)
        is QuestCondition.DistanceToWithHeight -> QuestCondition.DistanceToWithHeight(x, h, y, range, inst)
        is QuestCondition.DistanceFromWithHeight -> QuestCondition.DistanceFromWithHeight(x, h, y, range, inst)
        else -> orig
    }

    private data class DistanceParts(val x: Double, val h: Double, val y: Double, val range: Int, val instance: Boolean)

    private fun parseCsvInts(s: String): List<Int> =
        s.split(',').mapNotNull { it.trim().toIntOrNull() }
}
