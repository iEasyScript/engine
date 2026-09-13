package com.projectx.ui.tabs

import java.lang.reflect.InvocationTargetException
import com.projectx.script.*
import com.projectx.ui.ScriptLibrary
import com.projectx.ui.UI
import com.projectx.ui.UIState
import com.projectx.ui.UiChrome
import com.projectx.ui.backend.dsl.boolState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiChildFlags
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.dsl.utils.ImGuiTableColumnFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTreeNodeFlags

object ScriptsTab {
    val statusLabels = listOf("All", "Running", "Stopped")
    var frame = 0L

    private const val SEARCH_WIDTH = 220f
    private const val FILTER_WIDTH = 110f
    private const val STAR_SIZE = 14f
    private const val FAVORITE_BUTTON_WIDTH = 24f
    private const val FAVORITE_COLUMN_WIDTH = 30f
    private const val META_COLUMN_WIDTH = 150f
    private const val ACTION_BUTTON_WIDTH = 72f
    private const val ACTIONS_COLUMN_WIDTH = 156f

    private var cachedFilteredList: List<ScriptMetadata>? = null
    private var lastQuery = ""
    private var lastFilterIdx = 0
    private var lastScriptsVersion = 0
    private var lastRunningStates: Map<Class<out Script>, Boolean> = emptyMap()
    private var lastFavorites: Set<Class<out Script>> = emptySet()
    private var lastLibraryVersion = -1

    private fun LayoutScope.scriptCounts() {
        val inLibrary = ScriptLibrary.size()
        val running = ScriptExecutor.scripts.values.count { ScriptExecutor.isScriptRunning(it.scriptClass) }
        val shown = getFilteredScripts().size

        pushStyleColor(ImGuiCol.Text, if (running > 0) ImGuiColors.ACCENT_SUCCESS else ImGuiColors.TEXT_DISABLED)
        text("$running running")
        popStyleColor(1)
        sameLine()
        pushStyleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED)
        text(if (shown == inLibrary) "· $inLibrary in your library" else "· $shown of $inLibrary shown")
        popStyleColor(1)
    }

    private fun getFilteredScripts(): List<ScriptMetadata> {
        val query = UIState.scriptSearchText.value.trim().lowercase()
        val filterIdx = UIState.statusFilterIndex.value
        val scriptsVersion = ScriptExecutor.scripts.hashCode()
        val currentRunningStates = ScriptExecutor.scripts.values.associate { 
            it.scriptClass to ScriptExecutor.isScriptRunning(it.scriptClass)
        }
        val currentFavorites = UIState.favoriteScripts.toSet()
        val libraryVersion = ScriptLibrary.version

        if (cachedFilteredList != null &&
            query == lastQuery &&
            filterIdx == lastFilterIdx &&
            scriptsVersion == lastScriptsVersion &&
            currentRunningStates == lastRunningStates &&
            currentFavorites == lastFavorites &&
            libraryVersion == lastLibraryVersion) {
            return cachedFilteredList!!
        }

        // A running script stays listed after it is removed, so it can still be stopped from here.
        val sorted = ScriptExecutor.scripts.values
            .filter { ScriptLibrary.contains(it) || currentRunningStates[it.scriptClass] == true }
            .sortedWith(
                compareByDescending<ScriptMetadata> { currentFavorites.contains(it.scriptClass) }
                    .thenByDescending { currentRunningStates[it.scriptClass] ?: false }
                    .thenBy { it.name.lowercase() }
            )

        val filtered = sorted.filter { meta ->
            val isRunning = currentRunningStates[meta.scriptClass] ?: false
            val matchesQuery = query.isEmpty() || 
                meta.name.lowercase().contains(query) || 
                meta.author.lowercase().contains(query)
            val matchesStatus = when (filterIdx) {
                1 -> isRunning
                2 -> !isRunning
                else -> true
            }
            matchesQuery && matchesStatus
        }

        cachedFilteredList = filtered
        lastQuery = query
        lastFilterIdx = filterIdx
        lastScriptsVersion = scriptsVersion
        lastRunningStates = currentRunningStates
        lastFavorites = currentFavorites
        lastLibraryVersion = ScriptLibrary.version

        return filtered
    }
    
    fun ChildScope.render() {
        frame++
        // Only put the logo on the toolbar row when it actually loaded - an empty group + sameLine collapses
        // the row and hides the controls, and the texture can come back null (e.g. right after a reinject).
        // ImGui draws a widget's label to its right, which reads as a stray word beside the field.
        // "##" keeps the id and suppresses the label so captions can precede the widget.
        alignTextToFramePadding()
        text("Search")
        sameLine()
        setNextItemWidth(SEARCH_WIDTH)
        inputText("##script-search", UIState.scriptSearchText)
        sameLine()
        alignTextToFramePadding()
        text("Status")
        sameLine()
        setNextItemWidth(FILTER_WIDTH)
        combo("##script-status", UIState.statusFilterIndex, statusLabels)
        sameLine()
        button("Reload") { ScriptExecutor.loadScripts() }
        sameLine()
        button("Stop all") { ScriptExecutor.stopAll() }

        scriptCounts()
        spacing()

        child("ScriptScrollArea", height = -8f, childFlags = ImGuiChildFlags.Borders) {
            if (getFilteredScripts().isEmpty()) {
                styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                    textWrapped(
                        if (ScriptLibrary.size() == 0) "Your library is empty. Open the Store tab and add the scripts you want to use."
                        else "No scripts in your library match the search."
                    )
                }
                button("Open the Store") { UIState.selectedTab = UI.Tab.STORE }
            }
            getFilteredScripts().forEach { meta ->
                val isRunning = ScriptExecutor.isScriptRunning(meta.scriptClass)
                val isFavorite = UIState.favoriteScripts.contains(meta.scriptClass)
                val id = meta.scriptClass.name
                var isExpanded = false

                // One table per row so the description can be emitted outside it. ImGui clips a cell to its
                // column and has no cell spanning, so a description drawn inside the row would stay boxed in
                // the name column; the columns still line up because every row's table shares these widths.
                table(
                    id = "ScriptRow##$id",
                    columns = 4,
                    flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.NoPadOuterX,
                ) {
                    setupColumn("fav", ImGuiTableColumnFlags.WidthFixed, FAVORITE_COLUMN_WIDTH)
                    setupColumn("name", ImGuiTableColumnFlags.WidthStretch)
                    setupColumn("meta", ImGuiTableColumnFlags.WidthFixed, META_COLUMN_WIDTH)
                    setupColumn("actions", ImGuiTableColumnFlags.WidthFixed, ACTIONS_COLUMN_WIDTH)

                    nextRow()

                    nextColumn()
                    renderFavoriteToggle(meta, isFavorite)

                    nextColumn()
                    // Everything actionable stays on the row; only the description is behind the disclosure.
                    pushStyleColor(
                        ImGuiCol.Text,
                        if (isRunning) ImGuiColors.ACCENT_SUCCESS else ImGuiColors.TEXT_PRIMARY
                    )
                    isExpanded = treeNodeToggle(
                        "${meta.name}##$id",
                        ImGuiTreeNodeFlags.SpanAvailWidth or ImGuiTreeNodeFlags.FramePadding,
                    )
                    popStyleColor(1)

                    nextColumn()
                    alignTextToFramePadding()
                    styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                        text("v${meta.version}  ${meta.author}")
                    }

                    nextColumn()
                    if (isRunning) {
                        button("Stop##$id", width = ACTION_BUTTON_WIDTH) {
                            ScriptExecutor.deactivate(meta.scriptClass)
                        }
                    } else {
                        button("Start##$id", width = ACTION_BUTTON_WIDTH) {
                            try {
                                val instance = meta.scriptClass.getDeclaredConstructor().newInstance()
                                if (instance is ConfigurableScript) ScriptConfigStore.applyTo(instance)
                                ScriptExecutor.activate(instance)
                            } catch (e: Exception) {
                                val cause = (e as? InvocationTargetException)?.targetException ?: e
                                println("Failed to start ${meta.name}: ${cause.message ?: cause::class.simpleName}")
                                cause.printStackTrace()
                            }
                        }
                    }
                    if (ConfigurableScript::class.java.isAssignableFrom(meta.scriptClass)) {
                        sameLine()
                        button("Settings##$id", width = ACTION_BUTTON_WIDTH) {
                            val windowState = UIState.openConfigWindows.getOrPut(meta) {
                                boolState(true)
                            }
                            windowState.value = true
                        }
                    }
                }

                if (isExpanded) {
                    indent()
                    styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                        textWrapped(meta.description.ifBlank { "No description." })
                    }
                    if (ScriptLibrary.contains(meta)) {
                        smallButton("Remove from library##remove_$id") { ScriptLibrary.remove(meta) }
                    }
                    unindent()
                }
            }
        }
    }

    private fun LayoutScope.renderFavoriteToggle(meta: ScriptMetadata, isFavorite: Boolean) {
        val toggle = {
            if (isFavorite) {
                UIState.favoriteScripts.remove(meta.scriptClass)
            } else {
                UIState.favoriteScripts.add(meta.scriptClass)
            }
            UIState.saveFavorites()
        }
        val tint = if (isFavorite) ImGuiColors.ACCENT_SECONDARY else ImGuiColors.GRAY_LIGHTEST
        val texture = if (isFavorite) UiChrome.starOn else UiChrome.starOff
        if (texture == null) {
            // Textures warm on the render thread, so the first frames after a reinject can miss them.
            styleColor(ImGuiCol.Text, tint) {
                button("*##fav_${meta.scriptClass.name}", width = FAVORITE_BUTTON_WIDTH) { toggle() }
            }
        } else {
            iconButton(
                id = "fav_${meta.scriptClass.name}",
                texture = texture,
                iconSize = STAR_SIZE,
                buttonWidth = FAVORITE_BUTTON_WIDTH,
                tintColor = tint,
                onClick = toggle,
            )
        }
        itemTooltip(if (isFavorite) "Remove from favorites" else "Add to favorites")
    }
}
