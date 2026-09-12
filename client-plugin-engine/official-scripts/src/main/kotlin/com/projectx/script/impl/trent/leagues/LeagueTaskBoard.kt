package com.projectx.script.impl.trent.leagues

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.ui.backend.dsl.ImGuiDsl.backgroundDrawList
import com.projectx.ui.backend.dsl.ImGuiDsl.window
import com.projectx.ui.backend.dsl.boolState
import com.projectx.ui.backend.dsl.intState
import com.projectx.ui.backend.dsl.stringState
import com.projectx.ui.backend.dsl.scopes.button
import com.projectx.ui.backend.dsl.scopes.checkbox
import com.projectx.ui.backend.dsl.scopes.combo
import com.projectx.ui.backend.dsl.scopes.inputText
import com.projectx.ui.backend.dsl.scopes.itemTooltip
import com.projectx.ui.backend.dsl.scopes.progressBar
import com.projectx.ui.backend.dsl.scopes.sameLine
import com.projectx.ui.backend.dsl.scopes.selectable
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.smallButton
import com.projectx.ui.backend.dsl.scopes.table
import com.projectx.ui.backend.dsl.scopes.tabBar
import com.projectx.ui.backend.dsl.scopes.tableHeadersRow
import com.projectx.ui.backend.dsl.scopes.tableNextColumn
import com.projectx.ui.backend.dsl.scopes.tableNextRow
import com.projectx.ui.backend.dsl.scopes.tableSetupColumn
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.textWrapped
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags
import world.gregs.voidps.type.Tile

private const val ANY = 0
private const val ROW_LIMIT = 200

@ScriptDescription(
    name = "League Task Board",
    version = "2.0.0",
    author = "Trent",
    description = "Ranks every league task by how cheaply it can be finished and how far away it is",
)
class LeagueTaskBoard : Script() {

    private val hideCompleted = boolState(true)
    private val hideAggregates = boolState(true)
    private val onlyUnlocked = boolState(true)
    private val onlyActionable = boolState(true)
    private val regionFilter = intState(ANY)
    private val tierFilter = intState(ANY)
    private val maxRemaining = intState(0)
    private val search = stringState("")
    private val showFilters = boolState(false)
    private val highlightTargets = boolState(true)

    private var selected: LeagueTaskRoute? = null
    private var cached: List<LeagueTaskRoute> = emptyList()
    private var cachedAt = 0L

    override suspend fun loop() {
        delay(600)
    }

    private fun routes(): List<LeagueTaskRoute> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < REFRESH_MS && cached.isNotEmpty()) {
            return cached
        }
        cachedAt = now
        cached = LeagueRouting.rank(candidates(), localPlayer.tile)
        return cached
    }

    private fun candidates(): List<LeagueTask> {
        val unlockedAreas = LeagueTasks.unlockedAreas()
        val region = LeagueTasks.regions.getOrNull(regionFilter.value - 1)
        val query = search.value.trim().lowercase()
        val cap = maxRemaining.value
        return LeagueTasks.all.filter { task ->
            if (hideCompleted.value && task.completed) return@filter false
            if (hideAggregates.value && task.aggregate) return@filter false
            if (onlyUnlocked.value && !task.unlocked(unlockedAreas)) return@filter false
            if (onlyActionable.value && !task.actionable(unlockedAreas)) return@filter false
            if (region != null && task.region?.id != region.id) return@filter false
            if (tierFilter.value != ANY && task.tier != tierFilter.value) return@filter false
            if (cap > 0 && task.remaining > cap) return@filter false
            query.isEmpty() || task.description.lowercase().contains(query) || task.name.contains(query)
        }
    }

    override fun render() {
        highlightSelected()
        window("League Tasks") {
            val league = LeagueTasks.activeLeague
            if (league <= 0) {
                text("Not on a league world.")
                return@window
            }
            summary()
            separator()

            smallButton(if (showFilters.value) "Hide filters" else "Filters") {
                showFilters.value = !showFilters.value
            }
            sameLine()
            smallButton("Refresh") { cachedAt = 0 }
            sameLine()
            text("${cached.size} shown")
            if (showFilters.value) {
                filters()
            }
            separator()

            tabBar("league_tabs") {
                tabItem("Next up") { taskTable(routes()) }
                tabItem("Blocked") { blockedTable() }
                tabItem("Selected") { selectedDetail() }
            }
        }
    }

    private fun com.projectx.ui.backend.dsl.scopes.WindowScope.summary() {
        val all = LeagueTasks.all
        val unlocked = LeagueTasks.unlockedAreas()
        val done = all.count { it.completed }
        val actionable = all.count { it.actionable(unlocked) }
        val points = all.filter { it.completed }.sumOf { it.points }
        text("League ${LeagueTasks.activeLeague}: $done/${all.size} done  •  $actionable actionable  •  $points pts")
        progressBar(if (all.isEmpty()) 0f else done.toFloat() / all.size, overlay = "$done / ${all.size}")
        text("Regions: " + LeagueTasks.unlockedRegions().joinToString { it.name }.ifEmpty { "none" })
    }

    private fun com.projectx.ui.backend.dsl.scopes.WindowScope.filters() {
        checkbox("Hide completed", hideCompleted)
        sameLine()
        checkbox("Hide task sets", hideAggregates)
        itemTooltip("Task sets are completed by finishing many other tasks, never directly.")
        checkbox("Unlocked regions only", onlyUnlocked)
        sameLine()
        checkbox("Actionable only", onlyActionable)
        itemTooltip("Hides tasks whose levels or prerequisite tiers are not met yet.")
        combo("Region", regionFilter, listOf("Any") + LeagueTasks.regions.map { it.name })
        combo("Tier", tierFilter, listOf("Any") + (1..5).map { "Tier $it" })
        inputText("Search", search)
        combo("Max repeats", maxRemaining, REPEAT_CAPS.map { if (it == 0) "Any" else "<= $it" })
        checkbox("Highlight target in world", highlightTargets)
    }

    private fun com.projectx.ui.backend.dsl.scopes.ChildScope.taskTable(routes: List<LeagueTaskRoute>) {
        if (routes.isEmpty()) {
            text("Nothing matches. Loosen the filters.")
            return
        }
        table("league_tasks", 5, TABLE_FLAGS) {
            tableSetupColumn("T", width = 22f)
            tableSetupColumn("Task", width = 300f)
            tableSetupColumn("Where", width = 90f)
            tableSetupColumn("Dist", width = 52f)
            tableSetupColumn("Left", width = 86f)
            tableHeadersRow()
            for (route in routes.take(ROW_LIMIT)) {
                val task = route.task
                tableNextRow()
                tableNextColumn()
                text("${task.tier}")
                tableNextColumn()
                selectable(task.description.ifBlank { task.name }, selected?.task?.row == task.row) {
                    selected = route
                }
                itemTooltip(tooltip(route))
                tableNextColumn()
                text(task.region?.name ?: if (task.global) "Anywhere" else "Area ${task.area}")
                tableNextColumn()
                text(distanceLabel(route))
                tableNextColumn()
                if (task.target > 1) {
                    progressBar(task.progress.toFloat() / task.target, sizeX = 80f, overlay = "${task.progress}/${task.target}")
                } else {
                    text("-")
                }
            }
        }
        if (routes.size > ROW_LIMIT) {
            text("… ${routes.size - ROW_LIMIT} more, narrow the filters")
        }
    }

    private fun com.projectx.ui.backend.dsl.scopes.ChildScope.blockedTable() {
        val unlocked = LeagueTasks.unlockedAreas()
        val blocked = LeagueTasks.all
            .filter { !it.completed && !it.aggregate && it.unlocked(unlocked) && (!it.levelsMet || !it.chainMet) }
            .sortedBy { it.tier }
        if (blocked.isEmpty()) {
            text("Nothing is blocked in your unlocked regions.")
            return
        }
        text("${blocked.size} tasks blocked by levels or earlier tiers")
        table("league_blocked", 3, TABLE_FLAGS) {
            tableSetupColumn("T", width = 22f)
            tableSetupColumn("Task", width = 320f)
            tableSetupColumn("Blocked by", width = 200f)
            tableHeadersRow()
            for (task in blocked.take(ROW_LIMIT)) {
                tableNextRow()
                tableNextColumn()
                text("${task.tier}")
                tableNextColumn()
                text(task.description.ifBlank { task.name })
                tableNextColumn()
                text(blocker(task))
            }
        }
    }

    private fun com.projectx.ui.backend.dsl.scopes.ChildScope.selectedDetail() {
        val route = selected
        if (route == null) {
            text("Pick a task from Next up.")
            return
        }
        val task = route.task
        textWrapped(task.description.ifBlank { task.name })
        separator()
        text("Tier ${task.tier}  •  ${task.points} pts  •  ${task.region?.name ?: "Anywhere"}")
        text("Effort score ${route.effort}  •  ${distanceLabel(route)}")
        if (task.target > 1) {
            progressBar(task.progress.toFloat() / task.target, overlay = "${task.progress} / ${task.target}")
        }
        if (task.targets.matchedNames.isNotEmpty()) {
            textWrapped("Targets: " + task.targets.matchedNames.joinToString())
            text("${task.targets.locIds.size} scenery, ${task.targets.npcIds.size} npc, ${task.targets.objIds.size} item variants")
        }
        val missing = task.missingLevels()
        if (missing.isNotEmpty()) {
            textWrapped("Needs " + missing.joinToString { "${it.first.name.lowercase()} ${it.second}" })
        }
        val destination = route.destination
        if (destination != null) {
            separator()
            button("Walk here") { walkTo(destination, minimap = true) }
            sameLine()
            text("(${destination.x}, ${destination.y}, ${destination.level})")
        }
    }

    private fun highlightSelected() {
        val destination = selected?.destination ?: return
        if (!highlightTargets.value) return
        backgroundDrawList {
            tileArea(destination, 1, ImGuiColors.ACCENT_SUCCESS_SEMI)
            textOnTile(destination, ImGuiColors.TEXT_ACCENT, selected?.task?.name.orEmpty().take(28))
        }
    }

    private fun tooltip(route: LeagueTaskRoute): String {
        val task = route.task
        val lines = mutableListOf(task.name, "Tier ${task.tier}  ${task.points} pts")
        if (task.target > 1) lines += "Progress ${task.progress}/${task.target}"
        if (task.targets.matchedNames.isNotEmpty()) lines += "Targets: " + task.targets.matchedNames.joinToString()
        if (!route.located) lines += "No known location for this task"
        return lines.joinToString("\n")
    }

    private fun distanceLabel(route: LeagueTaskRoute): String = when {
        !route.located -> "?"
        route.precise -> "${route.distance}"
        else -> "~${route.distance}"
    }

    private fun blocker(task: LeagueTask): String {
        val missing = task.missingLevels()
        if (missing.isNotEmpty()) {
            return missing.joinToString { "${it.first.name.lowercase()} ${it.second}" }
        }
        return "earlier tier"
    }

    private companion object {
        const val REFRESH_MS = 3_000L
        val REPEAT_CAPS = listOf(0, 5, 25, 100, 500)
        const val TABLE_FLAGS = ImGuiTableFlags.RowBg or ImGuiTableFlags.BordersInnerH or
            ImGuiTableFlags.SizingFixedFit or ImGuiTableFlags.ScrollY
    }
}
