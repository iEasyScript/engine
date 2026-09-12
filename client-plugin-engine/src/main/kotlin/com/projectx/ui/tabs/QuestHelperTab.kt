package com.projectx.ui.tabs

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.quest.data.Quest
import com.projectx.quest.data.QuestAction
import com.projectx.quest.data.QuestLibrary
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
import com.projectx.ui.backend.dsl.scopes.*
import world.gregs.voidps.gameval.Gameval
import com.projectx.ui.backend.dsl.utils.ImGuiDir

object QuestHelperTab {
    fun ChildScope.render() {
        val active = ActiveQuestState.selectedQuest
        if (active != null) renderActive(active) else renderPicker()
    }

    private fun ChildScope.renderPicker() {
        section("Helper")
        checkboxGrid(
            "quest-helper-toggles",
            listOf(
                "Enabled" to UIState.questHelperEnabled,
                "Show overlay" to UIState.questHelperShowOverlay,
                "Auto-advance" to UIState.questHelperAutoAdvance,
            ),
            columns = 3
        )

        section("Solvers")
        checkboxGrid(
            "quest-solvers",
            listOf(
                "Invention discovery" to UIState.inventionDiscoverySolver,
            )
        )

        section("Find a quest")
        properties("quest-filter") {
            row("Search") { inputText("##questSearch", UIState.questHelperFilter) }
        }
        checkboxGrid(
            "quest-visibility",
            listOf(
                "Show locked" to UIState.questHelperShowLocked,
                "Show completed" to UIState.questHelperShowCompleted,
            )
        )
        smallButton("Clear filter##questFilter") { UIState.questHelperFilter.value = "" }
        sameLine()
        smallButton("Reload") { QuestLibrary.reload() }

        val filter = UIState.questHelperFilter.value.trim().lowercase()
        val quests = QuestLibrary.quests
        val nameFiltered = if (filter.isEmpty()) quests else quests.filter { it.name.lowercase().contains(filter) }
        val showLocked = UIState.questHelperShowLocked.value
        val showCompleted = UIState.questHelperShowCompleted.value
        val applyAvailability = loggedIn()
        val visible = if (!applyAvailability) nameFiltered else nameFiltered.filter { q ->
            val status = QuestStatusEvaluator.statusOf(q)
            if (!showCompleted && status == QuestStatus.COMPLETED) return@filter false
            if (!showLocked && QuestStatusEvaluator.isLocked(q)) return@filter false
            true
        }

        child("QuestPicker", height = 160f) {
            if (quests.isEmpty()) {
                if (QuestLibrary.loading) {
                    text("Loading quests...")
                } else {
                    text("No quest data loaded.")
                    text("Add per-quest JSON files under client-plugin-engine/src/main/resources/quest-data/quests/")
                }
                return@child
            }
            if (visible.isEmpty()) {
                text("No quests match the current filter.")
                return@child
            }
            visible.take(200).forEach { q ->
                smallButton("${q.name} (${q.steps.size} steps)##quest_${q.slug}") {
                    ActiveQuestState.select(q)
                }
            }
            if (visible.size > 200) {
                separator()
                text("...and ${visible.size - 200} more - refine your search.")
            }
        }

        separator()
        val totalLoaded = QuestLibrary.quests.size
        val shown = visible.size
        if (QuestLibrary.loading && totalLoaded == 0) {
            text("Loading quest data...")
        } else if (applyAvailability && shown != totalLoaded) {
            text("Showing $shown of $totalLoaded quests from ${QuestLibrary.lastLoadSource}.")
        } else {
            text("Loaded $totalLoaded quests from ${QuestLibrary.lastLoadSource}.")
        }
    }

    private fun ChildScope.renderActive(active: Quest) {
        smallButton("Back") { ActiveQuestState.select(null) }
        sameLine()
        smallButton("Reload") {
            val slug = active.slug
            val keepStep = ActiveQuestState.currentStepIndex
            QuestLibrary.reload()
            QuestLibrary.bySlug(slug)?.let {
                ActiveQuestState.select(it)
                ActiveQuestState.setStep(keepStep)
            }
        }
        sameLine()
        smallButton("Edit...") { QuestEditorState.beginEdit(active) }
        sameLine()
        text(active.name)

        checkboxGrid(
            "quest-active-toggles",
            listOf(
                "Enabled" to UIState.questHelperEnabled,
                "Overlay" to UIState.questHelperShowOverlay,
                "Auto-advance" to UIState.questHelperAutoAdvance,
            ),
            columns = 3
        )

        renderNavRow(active)

        separator()

        val idx = ActiveQuestState.currentStepIndex
        if (idx >= active.steps.size) {
            text("${active.name} - all steps complete!")
            button("Restart") { ActiveQuestState.reset() }
            return
        }

        if (idx == 0) renderRequirementsBlock(active)

        val step = active.steps[idx]
        step.title?.let { if (it.isNotBlank()) text(it) }
        step.text?.let { if (it.isNotBlank()) textWrapped(stripHtml(it)) }
        step.warning?.let { if (it.isNotBlank()) textWrapped("! ${stripHtml(it)}") }
        step.tpHint?.let { hint ->
            text(">> Teleport (${hint.type}): ${hint.hover}")
            hint.url?.let { text("    asset: $it") }
        }

        val solverHints = step.solverId?.let {
            safe { QuestSolverRegistry.overlayActionsFor(active, step, idx, System.currentTimeMillis() / 250) }
        } ?: emptyList()
        if (step.actions.isNotEmpty() || solverHints.isNotEmpty()) {
            section("Next")
            step.actions.forEach { describeAction(it) }
            solverHints.forEach { describeAction(it) }
        }

        if (step.jumpconditions.isNotEmpty() && loggedIn()) {
            section("Jump back if")
            step.jumpconditions.forEach { c ->
                val met = safe { ConditionEvaluator.isMet(c) } ?: false
                text("  ${if (met) "[x]" else "[ ]"} ${ConditionEvaluator.describe(c)} -> step ${idx + 1 + step.jumpOffset}")
            }
        }

        val items = step.neededItems.ifEmpty { active.neededItems.filter { it.duringQuest } }
        if (items.isNotEmpty() && loggedIn()) {
            section("Items")
            items.forEach { req ->
                val have = if (req.itemId >= 0) (safe { inventory.count(req.itemId) } ?: 0) else 0
                val ok = have >= req.quantity
                text("  ${if (ok) "[x]" else "[ ]"} ${req.name}  ($have / ${req.quantity})")
            }
        }
        if (step.recommendedItems.isNotEmpty() && loggedIn()) {
            section("Recommended for this step")
            step.recommendedItems.forEach { req ->
                val have = if (req.itemId >= 0) (safe { inventory.count(req.itemId) } ?: 0) else 0
                text("  · ${req.name}  ($have / ${req.quantity})")
            }
        }

        if (step.postconditions.isNotEmpty() && loggedIn()) {
            section("Until")
            step.postconditions.forEach { c ->
                val met = safe { ConditionEvaluator.isMet(c) } ?: false
                text("  ${if (met) "[x]" else "[ ]"} ${ConditionEvaluator.describe(c)}")
            }
        }
    }

    private fun ChildScope.renderNavRow(active: Quest) {
        val total = active.steps.size
        val idx = ActiveQuestState.currentStepIndex
        arrowButton("questPrev", ImGuiDir.Left, "Prev") { ActiveQuestState.previous() }
        itemTooltip("Previous step")
        sameLine()
        arrowButton("questNext", ImGuiDir.Right, "Next") { ActiveQuestState.next() }
        itemTooltip("Next step")
        sameLine()
        smallButton("Reset") { ActiveQuestState.reset() }
        if (active.stageVarbit >= 0) {
            sameLine()
            smallButton("Sync varbit") { ActiveQuestState.seekToVarbitStage() }
        }
        sameLine()
        val stepLabel = if (idx >= total) "done" else "Step ${idx + 1} / $total"
        text("   $stepLabel")
        if (active.stageVarbit >= 0) {
            sameLine()
            val live = if (loggedIn()) safe { varps.getVarBit(active.stageVarbit) } ?: -1 else -1
            text("   (${Gameval.varbitLabel(active.stageVarbit)} = $live / ${active.stageVarbitCompleteValue})")
        }
    }

    private fun ChildScope.renderRequirementsBlock(active: Quest) {
        if (active.prereqQuests.isEmpty()
            && active.questReqs.isEmpty()
            && active.combatNPCs.isEmpty()
            && active.recommendedItems.isEmpty()
            && !active.members
        ) return
        val live = loggedIn()
        if (active.members) {
            val unmet = live && (safe { QuestStatusEvaluator.isMembersBlocked(active) } ?: false)
            text("  ${reqMark(live, unmet)} Members only")
        }
        if (active.prereqQuests.isNotEmpty()) {
            text("Prerequisites:")
            active.prereqQuests.forEach { name ->
                val unmet = live && (safe { QuestStatusEvaluator.isPrereqUnmet(name) } ?: false)
                text("  ${reqMark(live, unmet)} $name")
            }
        }
        if (active.questReqs.isNotEmpty()) {
            text("Requirements:")
            active.questReqs.forEach { req ->
                val unmet = live && (safe { QuestStatusEvaluator.isReqUnmet(req) } ?: false)
                text("  ${reqMark(live, unmet)} ${describeQuestReq(req)}")
            }
        }
        if (active.recommendedItems.isNotEmpty()) {
            text("Recommended items:")
            active.recommendedItems.forEach { it -> text("  - ${it.name} ×${it.quantity}") }
        }
        if (active.combatNPCs.isNotEmpty()) {
            text("Combat enemies:")
            active.combatNPCs.forEach { it -> text("  - ${it.name} ×${it.quantity} (level ${it.level})") }
        }
        separator()
    }

    private fun ChildScope.describeAction(a: QuestAction) {
        val line = when (a) {
            is QuestAction.Direction -> buildString {
                append("  - ")
                append(if (a.tile) "Stand on tile " else "Travel to ")
                append("(${a.x.toInt()}, ${a.y.toInt()})")
                if (a.distance > 0) append(" - within ${a.distance} tiles")
                if (a.instance) append(" [instance]")
            }
            is QuestAction.ModelHighlight -> buildString {
                append("  - Find ${describeKind(a.kind)}: ${a.displayName}")
                if (a.priority == "all") append(" (all matches)")
                if (a.distance > 0) append(" - within ${a.distance} tiles")
                if (a.atLocation != null) {
                    append(" @ (${a.atLocation.x.toInt()}, ${a.atLocation.y.toInt()})")
                }
                if (a.instance) append(" [instance]")
            }
            is QuestAction.ConversationHighlight -> "  - Click dialog option: \"${a.text}\""
            is QuestAction.InventoryHighlight -> "  - Use inventory item: ${a.displayName}"
            is QuestAction.InterfaceComponentHighlight -> "  - Click: ${a.label.ifBlank { Gameval.componentLabel(a.interfaceId, a.componentId) }}"
            QuestAction.ContinueConversation -> "  - Press 'continue' in dialog"
            QuestAction.ResetInstance -> "  - Reset the instance"
            is QuestAction.PathGuide -> "  - Follow path (${a.waypoints.size} waypoints)${if (a.instance) " [instance]" else ""}"
            is QuestAction.TextHint -> "  - ${a.text}"
            is QuestAction.Unknown -> "  - (unsupported: ${a.name})"
        }
        text(line)
    }

    private fun describeKind(kind: String): String = when (kind) {
        "npc" -> "NPC"
        "object" -> "object"
        "item" -> "item"
        "model" -> "model"
        else -> "target"
    }

    private fun reqMark(live: Boolean, unmet: Boolean): String = when {
        !live -> "[?]"
        unmet -> "[ ]"
        else -> "[x]"
    }

    private fun describeQuestReq(req: com.projectx.quest.data.QuestReq): String = when (req.type) {
        "skill" -> "${req.level} ${req.name}${if (req.ironmanOnly) " (ironman)" else ""}"
        "combat" -> "Combat level ${req.level}"
        "questpoints" -> "${req.level} quest points"
        "misc" -> req.text ?: "(misc)"
        else -> "${req.type}: ${req.text ?: req.name ?: req.level}"
    }

    private fun loggedIn(): Boolean = safe { Bootstrap.client.mainState == MainState.LOGGED_IN } ?: false
}

private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Throwable) { null }
