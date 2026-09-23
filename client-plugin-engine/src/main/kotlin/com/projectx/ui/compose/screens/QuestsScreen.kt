package com.projectx.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestLibrary
import com.projectx.quest.data.QuestReq
import com.projectx.quest.editor.QuestEditorState
import com.projectx.quest.overlay.stripHtml
import com.projectx.quest.runtime.ActiveQuestState
import com.projectx.quest.runtime.ConditionEvaluator
import com.projectx.quest.runtime.QuestStatus
import com.projectx.quest.runtime.QuestStatusEvaluator
import com.projectx.quest.solver.QuestSolverRegistry
import com.projectx.script.api.inventory
import com.projectx.script.api.varps
import com.projectx.ui.UIState
import com.projectx.ui.compose.Feed
import com.projectx.ui.compose.GameThread
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.components.ChipGroup
import com.projectx.ui.compose.components.DataTable
import com.projectx.ui.compose.components.EmptyState
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.GlyphIcon
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.Pill
import com.projectx.ui.compose.components.ScreenScroll
import com.projectx.ui.compose.components.SearchField
import com.projectx.ui.compose.components.Section
import com.projectx.ui.compose.components.TableColumn
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette
import world.gregs.voidps.gameval.Gameval

/** Whether a requirement holds, where "unknown" means logged out and so not checkable. */
enum class Check { Met, Unmet, Unknown }

data class CheckLine(val check: Check, val text: String)

data class QuestPick(val slug: String, val name: String, val steps: Int, val status: QuestStatus?)

data class ActiveStep(
    val slug: String,
    val name: String,
    val stepIndex: Int,
    val stepCount: Int,
    val stage: String?,
    val hasStageVarbit: Boolean,
    val requirements: List<Pair<String, List<CheckLine>>>,
    val title: String?,
    val body: String?,
    val warning: String?,
    val teleport: String?,
    val next: List<String>,
    val jumpBack: List<CheckLine>,
    val items: List<CheckLine>,
    val recommended: List<String>,
    val until: List<CheckLine>,
)

data class QuestsSnapshot(
    val loading: Boolean,
    val loaded: Int,
    val source: String,
    val picks: List<QuestPick>,
    val active: ActiveStep?,
)

object QuestsModel {
    private const val MAX_PICKS = 300

    val feed = Feed(300) {
        val loggedIn = runCatching { Bootstrap.client.mainState == MainState.LOGGED_IN }.getOrDefault(false)
        val quests = QuestLibrary.quests
        QuestsSnapshot(
            loading = QuestLibrary.loading,
            loaded = quests.size,
            source = QuestLibrary.lastLoadSource,
            picks = picks(quests, loggedIn),
            active = ActiveQuestState.selectedQuest?.let { active(it, loggedIn) },
        )
    }

    fun select(slug: String?) = GameThread.post {
        ActiveQuestState.select(slug?.let { QuestLibrary.bySlug(it) })
        feed.invalidate()
    }

    fun step(action: () -> Unit) = GameThread.post { action(); feed.invalidate() }

    fun reloadKeepingStep(slug: String) = GameThread.post {
        val keep = ActiveQuestState.currentStepIndex
        QuestLibrary.reload()
        QuestLibrary.bySlug(slug)?.let {
            ActiveQuestState.select(it)
            ActiveQuestState.setStep(keep)
        }
        feed.invalidate()
    }

    fun edit(slug: String) = GameThread.post { QuestLibrary.bySlug(slug)?.let { QuestEditorState.beginEdit(it) } }

    private fun picks(quests: List<Quest>, loggedIn: Boolean): List<QuestPick> {
        val filter = UIState.questHelperFilter.value.trim().lowercase()
        return quests.asSequence()
            .filter { filter.isEmpty() || it.name.lowercase().contains(filter) }
            .mapNotNull { q ->
                val status = if (loggedIn) runCatching { QuestStatusEvaluator.statusOf(q) }.getOrNull() else null
                if (loggedIn) {
                    if (!UIState.questHelperShowCompleted.value && status == QuestStatus.COMPLETED) return@mapNotNull null
                    if (!UIState.questHelperShowLocked.value && runCatching { QuestStatusEvaluator.isLocked(q) }.getOrDefault(false)) return@mapNotNull null
                }
                QuestPick(q.slug, q.name, q.steps.size, status)
            }
            .take(MAX_PICKS)
            .toList()
    }

    private fun active(q: Quest, live: Boolean): ActiveStep {
        val index = ActiveQuestState.currentStepIndex
        val step = q.steps.getOrNull(index)
        fun check(unmet: () -> Boolean): Check = if (!live) Check.Unknown else if (safe(unmet) == true) Check.Unmet else Check.Met
        fun met(condition: () -> Boolean): Check = if (safe(condition) == true) Check.Met else Check.Unmet

        val requirements = if (index != 0) emptyList() else buildList {
            if (q.members) add("Membership" to listOf(CheckLine(check { QuestStatusEvaluator.isMembersBlocked(q) }, "Members only")))
            if (q.prereqQuests.isNotEmpty()) add("Quests" to q.prereqQuests.map { CheckLine(check { QuestStatusEvaluator.isPrereqUnmet(it) }, it) })
            if (q.questReqs.isNotEmpty()) add("Requirements" to q.questReqs.map { CheckLine(check { QuestStatusEvaluator.isReqUnmet(it) }, describe(it)) })
            if (q.recommendedItems.isNotEmpty()) add("Recommended items" to q.recommendedItems.map { CheckLine(Check.Unknown, "${it.name} x${it.quantity}") })
            if (q.combatNPCs.isNotEmpty()) add("Enemies" to q.combatNPCs.map { CheckLine(Check.Unknown, "${it.name} x${it.quantity} (level ${it.level})") })
        }
        val stage = if (q.stageVarbit >= 0) {
            val value = if (live) safe { varps.getVarBit(q.stageVarbit) } ?: -1 else -1
            "${Gameval.varbitLabel(q.stageVarbit)} = $value / ${q.stageVarbitCompleteValue}"
        } else null

        val solverHints = step?.solverId?.let {
            safe { QuestSolverRegistry.overlayActionsFor(q, step, index, System.currentTimeMillis() / 250) }
        }.orEmpty()
        val items = step?.neededItems?.ifEmpty { q.neededItems.filter { it.duringQuest } }.orEmpty()

        return ActiveStep(
            slug = q.slug,
            name = q.name,
            stepIndex = index,
            stepCount = q.steps.size,
            stage = stage,
            hasStageVarbit = q.stageVarbit >= 0,
            requirements = requirements,
            title = step?.title?.takeIf { it.isNotBlank() },
            body = step?.text?.takeIf { it.isNotBlank() }?.let { stripHtml(it) },
            warning = step?.warning?.takeIf { it.isNotBlank() }?.let { stripHtml(it) },
            teleport = step?.tpHint?.let { "Teleport (${it.type}): ${it.hover}" },
            next = (step?.actions.orEmpty() + solverHints).map { describe(it) },
            jumpBack = if (!live || step == null) emptyList() else step.jumpconditions.map { c ->
                CheckLine(met { ConditionEvaluator.isMet(c) }, "${ConditionEvaluator.describe(c)}  ->  step ${index + 1 + step.jumpOffset}")
            },
            items = if (!live) emptyList() else items.map { req ->
                val have = if (req.itemId >= 0) safe { inventory.count(req.itemId) } ?: 0 else 0
                CheckLine(if (have >= req.quantity) Check.Met else Check.Unmet, "${req.name}  ($have / ${req.quantity})")
            },
            recommended = if (!live) emptyList() else step?.recommendedItems.orEmpty().map { req ->
                val have = if (req.itemId >= 0) safe { inventory.count(req.itemId) } ?: 0 else 0
                "${req.name}  ($have / ${req.quantity})"
            },
            until = if (!live || step == null) emptyList() else step.postconditions.map { c -> CheckLine(met { ConditionEvaluator.isMet(c) }, ConditionEvaluator.describe(c)) },
        )
    }

    private fun describe(req: QuestReq): String = when (req.type) {
        "skill" -> "${req.level} ${req.name}${if (req.ironmanOnly) " (ironman)" else ""}"
        "combat" -> "Combat level ${req.level}"
        "questpoints" -> "${req.level} quest points"
        "misc" -> req.text ?: "(misc)"
        else -> "${req.type}: ${req.text ?: req.name ?: req.level}"
    }

    private fun describe(a: QuestAction): String = when (a) {
        is QuestAction.Direction -> buildString {
            append(if (a.tile) "Stand on tile " else "Travel to ")
            append("(${a.x.toInt()}, ${a.y.toInt()})")
            if (a.distance > 0) append(" within ${a.distance} tiles")
            if (a.instance) append(" [instance]")
        }
        is QuestAction.ModelHighlight -> buildString {
            append("Find ${kind(a.kind)}: ${a.displayName}")
            if (a.priority == "all") append(" (all matches)")
            if (a.distance > 0) append(" within ${a.distance} tiles")
            if (a.atLocation != null) append(" at (${a.atLocation.x.toInt()}, ${a.atLocation.y.toInt()})")
            if (a.instance) append(" [instance]")
        }
        is QuestAction.ConversationHighlight -> "Pick the dialogue option \"${a.text}\""
        is QuestAction.InventoryHighlight -> "Use ${a.displayName} from your backpack"
        is QuestAction.InterfaceComponentHighlight -> "Click ${a.label.ifBlank { Gameval.componentLabel(a.interfaceId, a.componentId) }}"
        QuestAction.ContinueConversation -> "Continue the dialogue"
        QuestAction.ResetInstance -> "Reset the instance"
        is QuestAction.PathGuide -> "Follow the path (${a.waypoints.size} waypoints)${if (a.instance) " [instance]" else ""}"
        is QuestAction.TextHint -> a.text
        is QuestAction.Unknown -> "(not supported: ${a.name})"
    }

    private fun kind(kind: String) = when (kind) {
        "npc" -> "NPC"
        "object", "item", "model" -> kind
        else -> "target"
    }
}

private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Throwable) { null }

@Composable
fun QuestsScreen() {
    if (QuestEditorState.windowOpen.value) return QuestEditorScreen()
    val s = QuestsModel.feed.value ?: return
    if (s.active != null) ActiveQuest(s.active) else QuestPicker(s)
}

@Composable
private fun HelperToggles() {
    ChipGroup(
        listOf(
            "Helper on" to UIState.questHelperEnabled,
            "Show overlay" to UIState.questHelperShowOverlay,
            "Auto-advance" to UIState.questHelperAutoAdvance,
            "Invention discovery solver" to UIState.inventionDiscoverySolver,
        ),
    )
}

@Composable
private fun QuestPicker(s: QuestsSnapshot) {
    ScreenScroll {
        Section("Quest helper", note = "Guides you through a quest step by step, highlighting what to do in the world.") {
            HelperToggles()
        }
        Section(
            "Pick a quest",
            note = when {
                s.loading && s.loaded == 0 -> "Loading quest data..."
                else -> "${s.picks.size} of ${s.loaded} quests from ${s.source}"
            },
            actions = {
                SearchField(UIState.questHelperFilter, "Quest name", 220.dp)
                ActionButton("Reload", { GameThread.post { QuestLibrary.reload(); QuestsModel.feed.invalidate() } }, icon = Glyph.Refresh, height = 34.dp)
            },
        ) {
            ChipGroup(listOf("Include locked" to UIState.questHelperShowLocked, "Include completed" to UIState.questHelperShowCompleted))
            if (s.loaded == 0 && !s.loading) {
                EmptyState("No quest data", "Add per-quest JSON files under client-plugin-engine/src/main/resources/quest-data/quests/.")
            } else {
                DataTable(
                    listOf(TableColumn("Quest", 2.4f), TableColumn("Steps", width = 70.dp, mono = true), TableColumn("Status", width = 130.dp), TableColumn("", width = 96.dp)),
                    s.picks,
                    emptyText = if (s.loading) "Loading quest data..." else "No quests match. Try including locked or completed ones.",
                    key = { it.slug },
                ) { pick ->
                    text(pick.name)
                    text(pick.steps.toString())
                    cell { pick.status?.let { StatusPill(it) } }
                    cell { ActionButton("Start", { QuestsModel.select(pick.slug) }, tone = ButtonTone.Primary, height = 28.dp) }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: QuestStatus) = when (status) {
    QuestStatus.COMPLETED -> Pill("Completed", Palette.running)
    QuestStatus.IN_PROGRESS -> Pill("In progress", Palette.amber)
    QuestStatus.NOT_STARTED -> Pill("Not started", Palette.muted)
    QuestStatus.UNKNOWN -> Pill("Unknown", Palette.faint)
}

@Composable
private fun ActiveQuest(a: ActiveStep) {
    val type = LocalType.current
    val done = a.stepIndex >= a.stepCount
    ScreenScroll {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("All quests", { QuestsModel.select(null) }, icon = Glyph.ChevronLeft, height = 32.dp)
            Column(Modifier.weight(1f)) {
                BasicText(a.name, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(if (done) "Complete" else "Step ${a.stepIndex + 1} of ${a.stepCount}" + (a.stage?.let { "  ·  $it" } ?: ""), style = type.data)
            }
            ActionButton("Reload", { QuestsModel.reloadKeepingStep(a.slug) }, icon = Glyph.Refresh, height = 32.dp)
            ActionButton("Edit", { QuestsModel.edit(a.slug) }, height = 32.dp)
        }
        StepProgress(a.stepIndex, a.stepCount)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Previous", { QuestsModel.step { ActiveQuestState.previous() } }, icon = Glyph.ChevronLeft)
            ActionButton("Next step", { QuestsModel.step { ActiveQuestState.next() } }, tone = ButtonTone.Primary, icon = Glyph.ChevronRight)
            ActionButton("Restart", { QuestsModel.step { ActiveQuestState.reset() } })
            if (a.hasStageVarbit) ActionButton("Sync with game", { QuestsModel.step { ActiveQuestState.seekToVarbitStage() } })
            Spacer(Modifier.weight(1f))
            HelperToggles()
        }
        if (done) {
            EmptyState("${a.name} is done", "Every step is complete.") {
                ActionButton("Start over", { QuestsModel.step { ActiveQuestState.reset() } })
            }
            return@ScreenScroll
        }
        a.requirements.forEach { (title, lines) -> Section(title) { Checklist(lines) } }
        Section(a.title ?: "This step") {
            a.body?.let { BasicText(it, style = type.body.copy(color = Palette.text)) }
            a.warning?.let { Hint(it, color = Palette.amber) }
            a.teleport?.let { Hint(it) }
            if (a.next.isNotEmpty()) {
                BasicText("NEXT", style = type.eyebrow)
                a.next.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.ChevronRight, Palette.amber, 9.dp)
                        BasicText(line, style = type.label)
                    }
                }
            }
        }
        if (a.items.isNotEmpty()) Section("Items needed") { Checklist(a.items) }
        if (a.recommended.isNotEmpty()) Section("Recommended for this step") { a.recommended.forEach { Hint(it) } }
        if (a.until.isNotEmpty()) Section("Done when") { Checklist(a.until) }
        if (a.jumpBack.isNotEmpty()) Section("Goes back if") { Checklist(a.jumpBack) }
    }
}

@Composable
private fun StepProgress(index: Int, count: Int) {
    if (count <= 0) return
    val fraction = (index.toFloat() / count).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.line)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Palette.amber))
    }
}

@Composable
private fun Checklist(lines: List<CheckLine>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                when (line.check) {
                    Check.Met -> GlyphIcon(Glyph.Check, Palette.running, 11.dp)
                    Check.Unmet -> GlyphIcon(Glyph.Close, Palette.stop, 11.dp)
                    Check.Unknown -> Spacer(Modifier.size(11.dp).width(11.dp))
                }
                BasicText(line.text, style = LocalType.current.label.copy(color = if (line.check == Check.Unmet) Palette.text else Palette.muted))
            }
        }
    }
}
