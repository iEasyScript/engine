package com.projectx.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestCondition
import com.projectx.quest.data.QuestItemReq
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.data.WorldLocation
import com.projectx.quest.editor.MutableQuest
import com.projectx.quest.editor.MutableStep
import com.projectx.quest.editor.PickKind
import com.projectx.quest.editor.PickResult
import com.projectx.quest.editor.QuestActionFactory
import com.projectx.quest.editor.QuestConditionFactory
import com.projectx.quest.editor.QuestEditorState
import com.projectx.quest.editor.TilePickResolver
import com.projectx.quest.editor.currentPlayerTilePick
import com.projectx.quest.runtime.ActiveQuestState
import com.projectx.quest.runtime.ConditionEvaluator
import com.projectx.quest.runtime.QuestInstanceTracker
import com.projectx.quest.solver.QuestSolverRegistry
import com.projectx.ui.compose.Feed
import com.projectx.ui.compose.GameThread
import com.projectx.ui.compose.OverlayText
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.components.Card
import com.projectx.ui.compose.components.Dropdown
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.IconButton
import com.projectx.ui.compose.components.MenuButton
import com.projectx.ui.compose.components.NumberInput
import com.projectx.ui.compose.components.ScreenScroll
import com.projectx.ui.compose.components.Section
import com.projectx.ui.compose.components.SettingRow
import com.projectx.ui.compose.components.TextArea
import com.projectx.ui.compose.components.TextInput
import com.projectx.ui.compose.components.ToggleChip
import com.projectx.ui.compose.components.animatedColor
import com.projectx.ui.compose.components.press
import com.projectx.ui.compose.components.rememberHover
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette

/**
 * The quest being edited is a mutable draft Compose cannot watch, so every edit - typed, clicked or delivered by an
 * in-game pick - bumps [revision], and the editor redraws from the draft.
 */
object QuestEditorModel {
    var revision by mutableIntStateOf(0)
        private set

    val dialog = Feed(500) {
        if (!ConditionEvaluator.debugCaptureEnabled) emptyList()
        else ConditionEvaluator.captureAllDialogText().map { it.interfaceId to it.sample }
    }

    fun changed() {
        revision++
    }

    /** Main-logic thread: hands over a pick made in the world since the last frame. */
    fun refresh() {
        if (!QuestEditorState.windowOpen.value) return
        QuestEditorState.drainPickDelivery()
        dialog.refresh()
    }

    fun usePlayerTile(onHit: (TilePickResolver.Hit) -> Unit) = GameThread.post {
        currentPlayerTilePick()?.let { onHit(instanceAware(it)); changed() }
    }

    fun pickTile(onHit: (TilePickResolver.Hit) -> Unit) = QuestEditorState.beginPickTile { tx, ty, plane, height ->
        onHit(instanceAware(TilePickResolver.Hit(tx, ty, plane, height, 0f, 0f)))
        changed()
    }

    fun pick(accepts: Set<PickKind>, prompt: String, onPicked: (PickResult) -> Unit) =
        QuestEditorState.beginPick(accepts, prompt) { onPicked(it); changed() }

    fun reloadFromDisk(slug: String) = GameThread.post {
        val step = QuestEditorState.selectedStepIndex.value
        QuestLibrary.reload()
        QuestLibrary.bySlug(slug)?.let {
            QuestEditorState.beginEdit(it)
            QuestEditorState.selectedStepIndex.value = step.coerceIn(0, (it.steps.size - 1).coerceAtLeast(0))
        }
        changed()
    }

    fun save() = GameThread.post { QuestEditorState.save(); changed() }

    /** Inside an instance, tiles are stored relative to its origin and flagged, so they survive a new instance. */
    private fun instanceAware(hit: TilePickResolver.Hit): TilePickResolver.Hit {
        val origin = QuestInstanceTracker.origin ?: return hit.copy(instance = false)
        return hit.copy(tileX = hit.tileX - origin.x, tileY = hit.tileY - origin.y, instance = true)
    }
}

private val entityKinds = listOf("npc", "object", "item", "grounditem", "model")

private enum class NewAction(val label: String, val make: () -> QuestAction) {
    Direction("Direction", { QuestActionFactory.direction(0, 0) }),
    Model("Model highlight", { QuestActionFactory.modelHighlight() }),
    Conversation("Conversation option", { QuestActionFactory.conversationHighlight() }),
    Continue("Continue dialogue", { QuestActionFactory.continueConversation() }),
    Inventory("Inventory highlight", { QuestActionFactory.inventoryHighlight() }),
    Path("Path guide", { QuestActionFactory.pathGuide() }),
    Reset("Reset instance", { QuestActionFactory.resetInstance() }),
    Text("Text hint", { QuestActionFactory.textHint("") }),
    Component("Interface component", { QuestActionFactory.interfaceComponentHighlight() }),
}

private enum class NewCondition(val label: String, val make: () -> QuestCondition) {
    DistanceTo("Within distance of", { QuestConditionFactory.distanceTo(0, 0) }),
    DistanceFrom("Beyond distance of", { QuestConditionFactory.distanceFrom(0, 0) }),
    Contains("Inventory contains", { QuestConditionFactory.inventoryContains() }),
    Lacks("Inventory does not contain", { QuestConditionFactory.inventoryDoesNotContain() }),
    Visible("Model visible", { QuestConditionFactory.modelVisible() }),
    NotVisible("Model not visible", { QuestConditionFactory.modelNotVisible() }),
    ConversationText("Dialogue says", { QuestConditionFactory.conversationText() }),
    ChatText("Chat says", { QuestConditionFactory.chatText() }),
    ConversationActive("In a dialogue", { QuestCondition.ConversationActive }),
    ConversationInactive("Not in a dialogue", { QuestCondition.ConversationInactive }),
    InInstance("In an instance", { QuestCondition.InInstance }),
    NotInInstance("Not in an instance", { QuestCondition.NotInInstance }),
    Always("Always", { QuestCondition.Always }),
    Manual("Manual", { QuestCondition.Manual }),
    Started("Quest started", { QuestCondition.QuestStarted }),
    Complete("Quest complete", { QuestCondition.QuestComplete }),
    InterfaceOpen("Interface open", { QuestCondition.InterfaceOpen() }),
    NpcNearTile("NPC near tile", { QuestCondition.NpcNearTile() }),
}

@Composable
fun QuestEditorScreen() {
    val draft = QuestEditorState.draft
    QuestEditorModel.revision
    if (draft == null) {
        QuestEditorState.windowOpen.value = false
        return
    }
    val type = LocalType.current
    val index = QuestEditorState.selectedStepIndex.value.coerceIn(0, (draft.steps.size - 1).coerceAtLeast(0))

    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Back to guide", { QuestEditorState.windowOpen.value = false }, icon = Glyph.ChevronLeft, height = 32.dp)
            Column(Modifier.weight(1f)) {
                BasicText("Editing ${draft.name}", style = type.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = QuestEditorState.saveStatus.value
                BasicText(status.ifEmpty { "Unsaved changes stay in this draft until you save." }, style = type.dataSmall)
            }
            ActionButton("Save", { QuestEditorModel.save() }, tone = ButtonTone.Primary, height = 32.dp)
            ActionButton("Reload from disk", { QuestEditorModel.reloadFromDisk(draft.slug) }, height = 32.dp)
            ActionButton("Discard", { QuestEditorState.discard() }, tone = ButtonTone.Danger, height = 32.dp)
        }
        if (QuestEditorState.pickMode.value) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.amber.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "Picking: ${QuestEditorState.pickPrompt.value}" + if (QuestInstanceTracker.origin != null) " (instance-local)" else "",
                    style = type.label.copy(color = Palette.amber),
                    modifier = Modifier.weight(1f),
                )
                ActionButton("Cancel", { QuestEditorState.cancelPick() }, height = 28.dp)
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepList(draft, index, Modifier.width(230.dp).fillMaxHeight())
            ScreenScroll(Modifier.weight(1f)) {
                MetaCard(draft)
                if (draft.steps.isEmpty()) {
                    Card {
                        Hint("No steps yet.")
                        ActionButton("Add the first step", { draft.steps.add(MutableStep(title = "New step")); QuestEditorModel.changed() }, icon = Glyph.Plus)
                    }
                } else {
                    StepEditor(draft, index, draft.steps[index])
                }
                DialogCapture(draft)
            }
        }
    }
}

@Composable
private fun StepList(draft: MutableQuest, selected: Int, modifier: Modifier) {
    val live = ActiveQuestState.currentStepIndex
    LazyColumn(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(12.dp)).padding(6.dp)) {
        itemsIndexed(draft.steps) { i, step ->
            val hover = rememberHover()
            val label = step.title?.takeIf { it.isNotBlank() } ?: step.text?.replace(Regex("<[^>]+>"), "")?.take(40) ?: "Step ${i + 1}"
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(animatedColor(if (i == selected) Palette.hover else if (hover.hovered) Palette.raised else Color.Transparent))
                    .press(hover) { QuestEditorState.selectedStepIndex.value = i }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText("${i + 1}", style = LocalType.current.dataSmall.copy(color = if (i == live) Palette.running else Palette.faint), modifier = Modifier.width(22.dp))
                BasicText(label, style = LocalType.current.label.copy(color = if (i == selected) Palette.amber else Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MetaCard(draft: MutableQuest) {
    Section("Quest") {
        SettingRow("Slug") { BoundText(draft, "slug", { draft.slug }, { draft.slug = it.trim() }, "slug", 260.dp) }
        SettingRow("Display name") { BoundText(draft, "name", { draft.name }, { draft.name = it }, "Name", 260.dp) }
        SettingRow("Length tag") { BoundText(draft, "length", { draft.length.orEmpty() }, { draft.length = it.ifBlank { null } }, "e.g. Short", 160.dp) }
        SettingRow("Members") { ToggleChip("Members only", draft.members) { draft.members = it; QuestEditorModel.changed() } }
        SettingRow("Stage varbit", "The varbit whose value tracks progress, and its value once complete.") {
            NumberInput(draft.stageVarbit, { draft.stageVarbit = it; QuestEditorModel.changed() }, -1..100_000, width = 150.dp)
            NumberInput(draft.stageVarbitCompleteValue, { draft.stageVarbitCompleteValue = it; QuestEditorModel.changed() }, -1..100_000, width = 150.dp)
        }
    }
}

@Composable
private fun StepEditor(draft: MutableQuest, index: Int, step: MutableStep) {
    fun select(i: Int) {
        QuestEditorState.selectedStepIndex.value = i.coerceIn(0, (draft.steps.size - 1).coerceAtLeast(0))
        QuestEditorModel.changed()
    }
    Section(
        "Step ${index + 1} of ${draft.steps.size}",
        actions = {
            IconButton(Glyph.ChevronLeft, { select(index - 1) })
            IconButton(Glyph.ChevronRight, { select(index + 1) })
            IconButton(Glyph.ChevronUp, {
                if (index > 0) { draft.steps.add(index - 1, draft.steps.removeAt(index)); select(index - 1) }
            })
            IconButton(Glyph.ChevronDown, {
                if (index < draft.steps.lastIndex) { draft.steps.add(index + 1, draft.steps.removeAt(index)); select(index + 1) }
            })
            IconButton(Glyph.Plus, { draft.steps.add(index + 1, MutableStep(title = "New step")); select(index + 1) })
            IconButton(Glyph.Copy, { draft.steps.add(index + 1, MutableStep.fromStep(step.toStep())); select(index + 1) })
            IconButton(Glyph.Trash, { draft.steps.removeAt(index); select(index) }, activeTint = Palette.stop)
            ActionButton("Live step", { select(ActiveQuestState.currentStepIndex) }, height = 28.dp)
        },
    ) {
        SettingRow("Title") { BoundText(step, "title", { step.title.orEmpty() }, { step.title = it.ifBlank { null } }, "Step title", 380.dp) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("Text", style = LocalType.current.bodyStrong)
            val text = remember(step) { OverlayText({ step.text.orEmpty() }, { step.text = it.ifBlank { null }; QuestEditorModel.changed() }, 4096, multiline = true) }
            TextArea(text, "What the player should do", Modifier.fillMaxWidth())
        }
        SettingRow("Warning") { BoundText(step, "warning", { step.warning.orEmpty() }, { step.warning = it.ifBlank { null } }, "Shown in amber", 380.dp) }
        SettingRow("Solver", "${QuestSolverRegistry.ids().size} registered") {
            Dropdown(step.solverId, listOf<String?>(null) + QuestSolverRegistry.ids(), { step.solverId = it; QuestEditorModel.changed() }, width = 220.dp) { it ?: "None" }
        }
    }
    Section("Actions", note = "What the overlay highlights for this step.", actions = {
        MenuButton("Add action", NewAction.entries, { step.actions += it.make(); QuestEditorModel.changed() }) { it.label }
    }) {
        if (step.actions.isEmpty()) Hint("No actions.")
        step.actions.indices.forEach { i -> ActionCard(step.actions, i) }
    }
    ConditionsSection("Done when", "The step advances once all of these hold.", step.postconditions)
    Section("Go back when", note = "Jumps by the offset once all of these hold.") {
        SettingRow("Jump offset") { NumberInput(step.jumpOffset, { step.jumpOffset = it; QuestEditorModel.changed() }, -500..500, width = 140.dp) }
        ConditionList(step.jumpconditions)
    }
    Section("Items needed") { ItemList(step.neededItems) }
    Section("Items recommended") { ItemList(step.recommendedItems) }
}

@Composable
private fun ConditionsSection(title: String, note: String, target: MutableList<QuestCondition>) {
    Section(title, note = note) { ConditionList(target) }
}

@Composable
private fun ConditionList(target: MutableList<QuestCondition>) {
    Row {
        Spacer(Modifier.weight(1f))
        MenuButton("Add condition", NewCondition.entries, { target += it.make(); QuestEditorModel.changed() }) { it.label }
    }
    if (target.isEmpty()) Hint("None.")
    target.indices.forEach { i -> ConditionCard(target, i) }
}

@Composable
private fun ItemCard(title: String, list: MutableList<*>, index: Int, content: @Composable ColumnScope.() -> Unit) {
    @Suppress("UNCHECKED_CAST")
    val items = list as MutableList<Any?>
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.raised).border(1.dp, Palette.line, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = LocalType.current.bodyStrong, modifier = Modifier.weight(1f))
            IconButton(Glyph.ChevronUp, { if (index > 0) { items.add(index - 1, items.removeAt(index)); QuestEditorModel.changed() } })
            IconButton(Glyph.ChevronDown, { if (index < items.lastIndex) { items.add(index + 1, items.removeAt(index)); QuestEditorModel.changed() } })
            IconButton(Glyph.Trash, { items.removeAt(index); QuestEditorModel.changed() }, activeTint = Palette.stop)
        }
        content()
    }
}

@Composable
private fun ActionCard(actions: MutableList<QuestAction>, i: Int) {
    val action = actions[i]
    fun set(next: QuestAction) {
        actions[i] = next
        QuestEditorModel.changed()
    }
    ItemCard(actionLabel(action), actions, i) {
        when (action) {
            is QuestAction.Direction -> {
                Coordinates(action.x.toInt(), action.y.toInt(), action.heightFine.toInt()) { x, y, z ->
                    set(action.copy(x = x.toDouble(), y = y.toDouble(), heightFine = z.toDouble()))
                }
                Flags {
                    ToggleChip("Instance", action.instance) { set(action.copy(instance = it)) }
                    ToggleChip("Snap to tile", action.tile) { set(action.copy(tile = it)) }
                    Labeled("Auto-advance within") { NumberInput(action.distance, { set(action.copy(distance = it)) }, -1..200, width = 130.dp) }
                }
                TilePickRow { hit -> set(action.copy(x = hit.tileX.toDouble(), y = hit.tileY.toDouble(), heightFine = hit.heightFine.toDouble(), instance = hit.instance)) }
            }
            is QuestAction.ModelHighlight -> {
                Flags {
                    Dropdown(action.kind, entityKinds, { set(action.copy(kind = it)) }, width = 130.dp)
                    Labeled("Type id") { NumberInput(action.typeId, { set(action.copy(typeId = it)) }, -1..1_000_000, width = 150.dp) }
                }
                ListText(actions, i, "name", { (it as QuestAction.ModelHighlight).displayName }, { a, v -> (a as QuestAction.ModelHighlight).copy(displayName = v) }, "Display name")
                ListText(actions, i, "models", { (it as QuestAction.ModelHighlight).modelIds.joinToString(",") }, { a, v -> (a as QuestAction.ModelHighlight).copy(modelIds = csvInts(v)) }, "Model ids, comma separated")
                EntityPickRow { r -> (actions.getOrNull(i) as? QuestAction.ModelHighlight)?.let { actions[i] = it.copy(kind = kindString(r.kind), typeId = r.typeId, displayName = r.name) } }
                Flags {
                    ToggleChip("Highlight all matches", action.priority == "all") { set(action.copy(priority = if (it) "all" else null)) }
                    ToggleChip("Instance", action.instance) { set(action.copy(instance = it)) }
                    Labeled("Auto-advance within") { NumberInput(action.distance, { set(action.copy(distance = it)) }, -1..200, width = 130.dp) }
                    ToggleChip("Anchor to a location", action.atLocation != null) { set(action.copy(atLocation = if (it) WorldLocation(0.0, 0.0, 0.0) else null)) }
                }
                action.atLocation?.let { loc ->
                    Coordinates(loc.x.toInt(), loc.y.toInt(), loc.heightFine.toInt()) { x, y, z ->
                        set(action.copy(atLocation = WorldLocation(x.toDouble(), z.toDouble(), y.toDouble())))
                    }
                    TilePickRow { hit -> set(action.copy(atLocation = WorldLocation(hit.tileX.toDouble(), hit.heightFine.toDouble(), hit.tileY.toDouble()), instance = hit.instance)) }
                }
            }
            is QuestAction.ConversationHighlight ->
                ListText(actions, i, "text", { (it as QuestAction.ConversationHighlight).text }, { a, v -> (a as QuestAction.ConversationHighlight).copy(text = v) }, "Option text")
            is QuestAction.InventoryHighlight -> {
                Labeled("Item id") { NumberInput(action.itemId, { set(action.copy(itemId = it)) }, -1..1_000_000, width = 150.dp) }
                ListText(actions, i, "name", { (it as QuestAction.InventoryHighlight).displayName }, { a, v -> (a as QuestAction.InventoryHighlight).copy(displayName = v) }, "Display name")
                ListText(actions, i, "models", { (it as QuestAction.InventoryHighlight).modelIds.joinToString(",") }, { a, v -> (a as QuestAction.InventoryHighlight).copy(modelIds = csvInts(v)) }, "Model ids, comma separated")
                ItemPickRow { r -> (actions.getOrNull(i) as? QuestAction.InventoryHighlight)?.let { actions[i] = it.copy(itemId = r.typeId, displayName = r.name) } }
            }
            is QuestAction.InterfaceComponentHighlight -> {
                Flags {
                    Labeled("Interface") { NumberInput(action.interfaceId, { set(action.copy(interfaceId = it)) }, -1..100_000, width = 140.dp) }
                    Labeled("Component") { NumberInput(action.componentId, { set(action.copy(componentId = it)) }, -1..100_000, width = 140.dp) }
                    Labeled("Slot") { NumberInput(action.slotId, { set(action.copy(slotId = it)) }, -1..100_000, width = 130.dp) }
                }
                ListText(actions, i, "label", { (it as QuestAction.InterfaceComponentHighlight).label }, { a, v -> (a as QuestAction.InterfaceComponentHighlight).copy(label = v) }, "Label")
                PickButton("Pick component", setOf(PickKind.COMPONENT), "click the interface component to capture") { r ->
                    (actions.getOrNull(i) as? QuestAction.InterfaceComponentHighlight)?.let { actions[i] = it.copy(interfaceId = r.interfaceId, componentId = r.componentId, slotId = r.slotId) }
                }
            }
            QuestAction.ContinueConversation -> Hint("Press continue in the dialogue.")
            QuestAction.ResetInstance -> Hint("Reset the instance.")
            is QuestAction.PathGuide -> {
                Flags {
                    ToggleChip("Instance", action.instance) { set(action.copy(instance = it)) }
                    ActionButton("Add waypoint at my tile", {
                        QuestEditorModel.usePlayerTile { hit -> addWaypoint(actions, i, hit) }
                    }, icon = Glyph.Plus, height = 30.dp)
                    ActionButton("Add waypoint by pick", { QuestEditorModel.pickTile { hit -> addWaypoint(actions, i, hit) } }, icon = Glyph.Crosshair, height = 30.dp)
                }
                action.waypoints.forEachIndexed { w, wp ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText("${w + 1}.  ${wp.x.toInt()}, ${wp.y.toInt()}   height ${wp.heightFine.toInt()}", style = LocalType.current.data.copy(color = Palette.text))
                        IconButton(Glyph.Trash, { set(action.copy(waypoints = action.waypoints.toMutableList().apply { removeAt(w) })) }, activeTint = Palette.stop, size = 24.dp)
                    }
                }
            }
            is QuestAction.TextHint -> ListText(actions, i, "text", { (it as QuestAction.TextHint).text }, { a, v -> (a as QuestAction.TextHint).copy(text = v) }, "Hint text")
            is QuestAction.Unknown -> Hint("Not supported: ${action.name}")
        }
    }
}

private fun addWaypoint(actions: MutableList<QuestAction>, i: Int, hit: TilePickResolver.Hit) {
    val current = actions.getOrNull(i) as? QuestAction.PathGuide ?: return
    actions[i] = current.copy(
        waypoints = current.waypoints + QuestAction.PathGuide.Waypoint(hit.tileX.toDouble(), hit.heightFine.toDouble(), hit.tileY.toDouble()),
        instance = hit.instance,
    )
}

@Composable
private fun ConditionCard(target: MutableList<QuestCondition>, i: Int) {
    val c = target[i]
    fun set(next: QuestCondition) {
        target[i] = next
        QuestEditorModel.changed()
    }
    ItemCard(conditionLabel(c), target, i) {
        when (c) {
            is QuestCondition.DistanceTo, is QuestCondition.DistanceFrom,
            is QuestCondition.DistanceToWithHeight, is QuestCondition.DistanceFromWithHeight -> {
                val d = unpack(c)
                Coordinates(d.x.toInt(), d.y.toInt(), d.height.toInt()) { x, y, z -> set(repack(c, x.toDouble(), z.toDouble(), y.toDouble(), d.range, d.instance)) }
                Flags {
                    Labeled("Range") { NumberInput(d.range, { set(repack(c, d.x, d.height, d.y, it, d.instance)) }, 0..500, width = 130.dp) }
                    ToggleChip("Instance", d.instance) { set(repack(c, d.x, d.height, d.y, d.range, it)) }
                }
                TilePickRow { hit -> set(repack(c, hit.tileX.toDouble(), hit.heightFine.toDouble(), hit.tileY.toDouble(), d.range, hit.instance)) }
            }
            is QuestCondition.InventoryContains -> {
                Flags {
                    Labeled("Item id") { NumberInput(c.itemId, { set(c.copy(itemId = it)) }, -1..1_000_000, width = 150.dp) }
                    Labeled("Quantity") { NumberInput(c.quantity, { set(c.copy(quantity = it)) }, 1..Int.MAX_VALUE, width = 130.dp) }
                }
                ListText(target, i, "name", { (it as QuestCondition.InventoryContains).displayName }, { x, v -> (x as QuestCondition.InventoryContains).copy(displayName = v) }, "Display name")
                ItemPickRow { r -> (target.getOrNull(i) as? QuestCondition.InventoryContains)?.let { target[i] = it.copy(itemId = r.typeId, displayName = r.name) } }
            }
            is QuestCondition.InventoryDoesNotContain -> {
                Labeled("Item id") { NumberInput(c.itemId, { set(c.copy(itemId = it)) }, -1..1_000_000, width = 150.dp) }
                ListText(target, i, "name", { (it as QuestCondition.InventoryDoesNotContain).displayName }, { x, v -> (x as QuestCondition.InventoryDoesNotContain).copy(displayName = v) }, "Display name")
                ItemPickRow { r -> (target.getOrNull(i) as? QuestCondition.InventoryDoesNotContain)?.let { target[i] = it.copy(itemId = r.typeId, displayName = r.name) } }
            }
            is QuestCondition.ModelVisible -> {
                Flags {
                    Dropdown(c.kind, entityKinds, { set(c.copy(kind = it)) }, width = 130.dp)
                    Labeled("Type id") { NumberInput(c.typeId, { set(c.copy(typeId = it)) }, -1..1_000_000, width = 150.dp) }
                    Labeled("Quantity") { NumberInput(c.quantity, { set(c.copy(quantity = it)) }, 0..1000, width = 130.dp) }
                }
                ListText(target, i, "name", { (it as QuestCondition.ModelVisible).displayName }, { x, v -> (x as QuestCondition.ModelVisible).copy(displayName = v) }, "Display name")
                EntityPickRow { r -> (target.getOrNull(i) as? QuestCondition.ModelVisible)?.let { target[i] = it.copy(kind = kindString(r.kind), typeId = r.typeId, displayName = r.name) } }
                Flags {
                    ToggleChip("Animated", c.animated) { set(c.copy(animated = it)) }
                    ToggleChip("Instance", c.instance) { set(c.copy(instance = it)) }
                }
            }
            is QuestCondition.ModelNotVisible -> {
                Flags {
                    Dropdown(c.kind, entityKinds, { set(c.copy(kind = it)) }, width = 130.dp)
                    Labeled("Type id") { NumberInput(c.typeId, { set(c.copy(typeId = it)) }, -1..1_000_000, width = 150.dp) }
                    ToggleChip("Instance", c.instance) { set(c.copy(instance = it)) }
                }
                ListText(target, i, "name", { (it as QuestCondition.ModelNotVisible).displayName }, { x, v -> (x as QuestCondition.ModelNotVisible).copy(displayName = v) }, "Display name")
                EntityPickRow { r -> (target.getOrNull(i) as? QuestCondition.ModelNotVisible)?.let { target[i] = it.copy(kind = kindString(r.kind), typeId = r.typeId, displayName = r.name) } }
            }
            is QuestCondition.ConversationText -> ListText(target, i, "text", { (it as QuestCondition.ConversationText).text }, { x, v -> (x as QuestCondition.ConversationText).copy(text = v) }, "Dialogue text")
            is QuestCondition.ChatText -> ListText(target, i, "text", { (it as QuestCondition.ChatText).text }, { x, v -> (x as QuestCondition.ChatText).copy(text = v) }, "Chat text")
            is QuestCondition.CaptureConversationState -> {
                ListText(target, i, "pattern", { (it as QuestCondition.CaptureConversationState).pattern }, { x, v -> (x as QuestCondition.CaptureConversationState).copy(pattern = v) }, "Pattern")
                ListText(target, i, "key", { (it as QuestCondition.CaptureConversationState).key }, { x, v -> (x as QuestCondition.CaptureConversationState).copy(key = v) }, "Key")
            }
            is QuestCondition.StateEquals -> {
                ListText(target, i, "key", { (it as QuestCondition.StateEquals).key }, { x, v -> (x as QuestCondition.StateEquals).copy(key = v) }, "Key")
                ListText(target, i, "value", { (it as QuestCondition.StateEquals).value }, { x, v -> (x as QuestCondition.StateEquals).copy(value = v) }, "Value")
            }
            is QuestCondition.InCombatWith -> {
                Labeled("NPC id") { NumberInput(c.npcId, { set(c.copy(npcId = it)) }, -1..1_000_000, width = 150.dp) }
                ListText(target, i, "name", { (it as QuestCondition.InCombatWith).displayName }, { x, v -> (x as QuestCondition.InCombatWith).copy(displayName = v) }, "NPC name")
            }
            is QuestCondition.ItemClicked -> Labeled("Item id") { NumberInput(c.itemId, { set(c.copy(itemId = it)) }, -1..1_000_000, width = 150.dp) }
            is QuestCondition.InterfaceOpen -> Labeled("Interface id") { NumberInput(c.interfaceId, { set(c.copy(interfaceId = it)) }, -1..100_000, width = 150.dp) }
            is QuestCondition.NpcNearTile -> {
                Labeled("NPC type id") { NumberInput(c.typeId, { set(c.copy(typeId = it)) }, -1..1_000_000, width = 150.dp) }
                ListText(target, i, "name", { (it as QuestCondition.NpcNearTile).displayName }, { x, v -> (x as QuestCondition.NpcNearTile).copy(displayName = v) }, "NPC name")
                PickButton("Pick NPC", setOf(PickKind.NPC), "examine an NPC") { r ->
                    (target.getOrNull(i) as? QuestCondition.NpcNearTile)?.let { target[i] = it.copy(typeId = r.typeId, displayName = r.name) }
                }
                Coordinates(c.tileX, c.tileY, c.plane, thirdLabel = "Plane") { x, y, p -> set(c.copy(tileX = x, tileY = y, plane = p)) }
                TilePickRow { hit -> (target.getOrNull(i) as? QuestCondition.NpcNearTile)?.let { target[i] = it.copy(tileX = hit.tileX, tileY = hit.tileY, plane = hit.plane, instance = hit.instance) } }
                Flags {
                    ToggleChip("Instance (local coordinates)", c.instance) { set(c.copy(instance = it)) }
                    Labeled("Distance") { NumberInput(c.distance, { set(c.copy(distance = it)) }, 0..200, width = 130.dp) }
                }
            }
            QuestCondition.Always, QuestCondition.Manual, QuestCondition.NotInInstance, QuestCondition.InInstance,
            QuestCondition.ChangedInstance, QuestCondition.Generic, QuestCondition.QuestStarted,
            QuestCondition.QuestComplete, QuestCondition.QuestInterfaceOpen,
            QuestCondition.ConversationActive, QuestCondition.ConversationInactive -> Unit
            is QuestCondition.Unknown -> Hint("Not supported: ${c.name}")
        }
    }
}

@Composable
private fun ItemList(items: MutableList<QuestItemReq>) {
    items.indices.forEach { i ->
        val req = items[i]
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberInput(req.itemId, { items[i] = req.copy(itemId = it); QuestEditorModel.changed() }, -1..1_000_000, width = 140.dp)
            NumberInput(req.quantity, { items[i] = req.copy(quantity = it); QuestEditorModel.changed() }, 1..Int.MAX_VALUE, width = 120.dp)
            ListText(items, i, "name", { it.name }, { r, v -> r.copy(name = v) }, "Item name", 200.dp)
            PickButton("Pick", setOf(PickKind.ITEM), "click or examine an item") { r ->
                items.getOrNull(i)?.let { items[i] = it.copy(itemId = r.typeId, name = r.name) }
            }
            IconButton(Glyph.Trash, { items.removeAt(i); QuestEditorModel.changed() }, activeTint = Palette.stop)
        }
    }
    ActionButton("Add item", { items += QuestItemReq(name = "", itemId = -1, quantity = 1, duringQuest = true); QuestEditorModel.changed() }, icon = Glyph.Plus, height = 30.dp)
}

@Composable
private fun DialogCapture(draft: MutableQuest) {
    val captures = QuestEditorModel.dialog.value.orEmpty()
    Section("Dialogue capture", note = "Debug help for dialogue conditions. The buttons write to the engine log.") {
        Flags {
            ToggleChip("Capture dialogue text", ConditionEvaluator.debugCaptureEnabled) { ConditionEvaluator.debugCaptureEnabled = it; QuestEditorModel.changed() }
            ActionButton("Log dialogue state", { ConditionEvaluator.logDialogState() }, height = 30.dp)
            ActionButton("Sweep interfaces 0-2000", { ConditionEvaluator.sweepAllInterfaces() }, height = 30.dp)
            ActionButton("Split all quests to files", {
                GameThread.post { QuestEditorState.saveStatus.value = "Split ${QuestLibrary.splitBundleToFiles(overwrite = false)} quests to per-quest files" }
            }, height = 30.dp)
        }
        if (ConditionEvaluator.debugCaptureEnabled) {
            if (captures.isEmpty()) Hint("No open dialogue produced any text.")
            captures.forEach { (interfaceId, sample) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText("[$interfaceId] $sample", style = LocalType.current.data.copy(color = Palette.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ActionButton("Add as \"dialogue says\"", {
                        val step = draft.steps.getOrNull(QuestEditorState.selectedStepIndex.value) ?: return@ActionButton
                        val snippet = sample.take(60).trim()
                        if (snippet.isNotEmpty()) {
                            step.postconditions += QuestCondition.ConversationText(snippet)
                            QuestEditorModel.changed()
                        }
                    }, height = 28.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Flags(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun Labeled(label: String, control: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label, style = LocalType.current.label.copy(color = Palette.muted))
        control()
    }
}

@Composable
private fun Coordinates(x: Int, y: Int, third: Int, thirdLabel: String = "Height", onChange: (Int, Int, Int) -> Unit) {
    Flags {
        Labeled("X") { NumberInput(x, { onChange(it, y, third) }, -1..100_000, width = 140.dp) }
        Labeled("Y") { NumberInput(y, { onChange(x, it, third) }, -1..100_000, width = 140.dp) }
        Labeled(thirdLabel) { NumberInput(third, { onChange(x, y, it) }, -100_000..100_000, width = 140.dp) }
    }
}

@Composable
private fun TilePickRow(onHit: (TilePickResolver.Hit) -> Unit) {
    Flags {
        ActionButton("Use my tile", { QuestEditorModel.usePlayerTile(onHit) }, icon = Glyph.Crosshair, height = 30.dp)
        if (QuestEditorState.pickMode.value) ActionButton("Cancel pick", { QuestEditorState.cancelPick() }, height = 30.dp)
        else ActionButton("Pick a tile", { QuestEditorModel.pickTile(onHit) }, height = 30.dp)
    }
}

@Composable
private fun EntityPickRow(onPicked: (PickResult) -> Unit) =
    PickButton("Pick in game", setOf(PickKind.OBJECT, PickKind.NPC, PickKind.ITEM, PickKind.GROUND_ITEM), "examine an object, NPC, item or ground item", onPicked)

@Composable
private fun ItemPickRow(onPicked: (PickResult) -> Unit) = PickButton("Pick item", setOf(PickKind.ITEM), "click or examine an item", onPicked)

@Composable
private fun PickButton(label: String, accepts: Set<PickKind>, prompt: String, onPicked: (PickResult) -> Unit) {
    if (QuestEditorState.pickMode.value) ActionButton("Cancel pick", { QuestEditorState.cancelPick() }, height = 30.dp)
    else ActionButton(label, { QuestEditorModel.pick(accepts, prompt, onPicked) }, icon = Glyph.Crosshair, height = 30.dp)
}

/** A field on the draft itself. Keyed by owner and name so the same field keeps focus while it is typed into. */
@Composable
private fun BoundText(owner: Any, name: String, read: () -> String, write: (String) -> Unit, placeholder: String, width: Dp) {
    val field = remember(owner, name) { OverlayText(read, { write(it); QuestEditorModel.changed() }, 256) }
    TextInput(field, placeholder, width)
}

/**
 * A text field on an immutable element of a list, replaced with an edited copy on every keystroke. Keyed by list and
 * position rather than by the element, which changes with each copy and would otherwise drop focus mid-word.
 */
@Composable
private fun <T> ListText(list: MutableList<T>, index: Int, name: String, get: (T) -> String, with: (T, String) -> T, placeholder: String, width: Dp = 380.dp) {
    val field = remember(list, index, name) {
        OverlayText(
            read = { list.getOrNull(index)?.let(get).orEmpty() },
            write = { value -> list.getOrNull(index)?.let { list[index] = with(it, value); QuestEditorModel.changed() } },
            maxLength = 256,
        )
    }
    TextInput(field, placeholder, width)
}

private fun csvInts(s: String): List<Int> = s.split(',').mapNotNull { it.trim().toIntOrNull() }

private fun kindString(kind: PickKind): String = when (kind) {
    PickKind.NPC -> "npc"
    PickKind.OBJECT -> "object"
    PickKind.ITEM -> "item"
    PickKind.GROUND_ITEM -> "grounditem"
    PickKind.TILE -> "model"
    PickKind.COMPONENT -> "component"
}

private fun actionLabel(a: QuestAction): String = when (a) {
    is QuestAction.Direction -> "Direction"
    is QuestAction.ModelHighlight -> "Model highlight"
    is QuestAction.ConversationHighlight -> "Conversation option"
    is QuestAction.InventoryHighlight -> "Inventory highlight"
    is QuestAction.InterfaceComponentHighlight -> "Interface component"
    QuestAction.ContinueConversation -> "Continue dialogue"
    QuestAction.ResetInstance -> "Reset instance"
    is QuestAction.PathGuide -> "Path guide (${a.waypoints.size} waypoints)"
    is QuestAction.TextHint -> "Text hint"
    is QuestAction.Unknown -> "Unknown (${a.name})"
}

private fun conditionLabel(c: QuestCondition): String = when (c) {
    is QuestCondition.DistanceTo -> "Within distance of"
    is QuestCondition.DistanceFrom -> "Beyond distance of"
    is QuestCondition.DistanceToWithHeight -> "Within distance of, with height"
    is QuestCondition.DistanceFromWithHeight -> "Beyond distance of, with height"
    is QuestCondition.InventoryContains -> "Inventory contains"
    is QuestCondition.InventoryDoesNotContain -> "Inventory does not contain"
    is QuestCondition.ModelVisible -> "Model visible"
    is QuestCondition.ModelNotVisible -> "Model not visible"
    is QuestCondition.ConversationText -> "Dialogue says"
    is QuestCondition.ChatText -> "Chat says"
    is QuestCondition.CaptureConversationState -> "Capture dialogue state"
    is QuestCondition.StateEquals -> "State equals"
    is QuestCondition.InCombatWith -> "In combat with"
    is QuestCondition.ItemClicked -> "Item clicked"
    QuestCondition.Always -> "Always"
    QuestCondition.Manual -> "Manual"
    QuestCondition.NotInInstance -> "Not in an instance"
    QuestCondition.InInstance -> "In an instance"
    QuestCondition.ChangedInstance -> "Changed instance"
    QuestCondition.Generic -> "Generic"
    QuestCondition.QuestStarted -> "Quest started"
    QuestCondition.QuestComplete -> "Quest complete"
    QuestCondition.QuestInterfaceOpen -> "Quest interface open"
    is QuestCondition.InterfaceOpen -> "Interface open"
    is QuestCondition.NpcNearTile -> "NPC near tile"
    QuestCondition.ConversationActive -> "In a dialogue"
    QuestCondition.ConversationInactive -> "Not in a dialogue"
    is QuestCondition.Unknown -> "Unknown (${c.name})"
}

private data class Distance(val x: Double, val height: Double, val y: Double, val range: Int, val instance: Boolean)

private fun unpack(c: QuestCondition): Distance = when (c) {
    is QuestCondition.DistanceTo -> Distance(c.x, c.heightFine, c.y, c.range, c.instance)
    is QuestCondition.DistanceFrom -> Distance(c.x, c.heightFine, c.y, c.range, c.instance)
    is QuestCondition.DistanceToWithHeight -> Distance(c.x, c.heightFine, c.y, c.range, c.instance)
    is QuestCondition.DistanceFromWithHeight -> Distance(c.x, c.heightFine, c.y, c.range, c.instance)
    else -> Distance(0.0, 0.0, 0.0, 0, false)
}

private fun repack(orig: QuestCondition, x: Double, height: Double, y: Double, range: Int, instance: Boolean): QuestCondition = when (orig) {
    is QuestCondition.DistanceTo -> QuestCondition.DistanceTo(x, height, y, range, instance)
    is QuestCondition.DistanceFrom -> QuestCondition.DistanceFrom(x, height, y, range, instance)
    is QuestCondition.DistanceToWithHeight -> QuestCondition.DistanceToWithHeight(x, height, y, range, instance)
    is QuestCondition.DistanceFromWithHeight -> QuestCondition.DistanceFromWithHeight(x, height, y, range, instance)
    else -> orig
}
