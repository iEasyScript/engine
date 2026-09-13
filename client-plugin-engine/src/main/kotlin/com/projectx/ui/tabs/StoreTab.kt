package com.projectx.ui.tabs

import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.ui.ScriptLibrary
import com.projectx.ui.ScriptSource
import com.projectx.ui.UI
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiChildFlags
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.dsl.utils.ImGuiTableColumnFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags
import com.projectx.ui.backend.dsl.utils.ImGuiTreeNodeFlags

/**
 * Every loaded script, to add to or remove from the library the Scripts tab lists. All scripts are free: adding one
 * only puts it in the library, there is no purchase step.
 */
object StoreTab {
    private val sourceLabels = listOf("All sources") + ScriptSource.entries.map { it.label }

    private const val SEARCH_WIDTH = 220f
    private const val FILTER_WIDTH = 130f
    private const val META_COLUMN_WIDTH = 190f
    private const val ACTION_COLUMN_WIDTH = 96f
    private const val ACTION_BUTTON_WIDTH = 88f

    private var cached: List<ScriptMetadata>? = null
    private var lastQuery = ""
    private var lastSource = 0
    private var lastScripts = 0

    fun ChildScope.render() {
        alignTextToFramePadding()
        text("Search")
        sameLine()
        setNextItemWidth(SEARCH_WIDTH)
        inputText("##store-search", UIState.storeSearchText)
        sameLine()
        alignTextToFramePadding()
        text("Source")
        sameLine()
        setNextItemWidth(FILTER_WIDTH)
        combo("##store-source", UIState.storeSourceIndex, sourceLabels)

        val shown = filtered()
        styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
            text("${shown.size} of ${ScriptExecutor.scripts.size} scripts · ${ScriptLibrary.size()} in your library · every script is free")
        }
        spacing()

        child("StoreScrollArea", height = -8f, childFlags = ImGuiChildFlags.Borders) {
            if (shown.isEmpty()) {
                styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                    textWrapped(if (ScriptExecutor.scripts.isEmpty()) "No scripts are loaded." else "No scripts match the search.")
                }
            }
            shown.forEach { renderRow(it) }
        }
    }

    private fun ChildScope.renderRow(meta: ScriptMetadata) {
        val id = meta.scriptClass.name
        val inLibrary = ScriptLibrary.contains(meta)
        var expanded = false

        table(id = "StoreRow##$id", columns = 3, flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.NoPadOuterX) {
            setupColumn("name", ImGuiTableColumnFlags.WidthStretch)
            setupColumn("meta", ImGuiTableColumnFlags.WidthFixed, META_COLUMN_WIDTH)
            setupColumn("action", ImGuiTableColumnFlags.WidthFixed, ACTION_COLUMN_WIDTH)
            nextRow()

            nextColumn()
            expanded = treeNodeToggle("${meta.name}##store_$id", ImGuiTreeNodeFlags.SpanAvailWidth or ImGuiTreeNodeFlags.FramePadding)

            nextColumn()
            alignTextToFramePadding()
            styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                text("${ScriptLibrary.sourceOf(meta).label} · ${meta.author}")
            }

            nextColumn()
            if (inLibrary) {
                styleColor(ImGuiCol.Text, ImGuiColors.ACCENT_SUCCESS) {
                    button("Added##store_add_$id", width = ACTION_BUTTON_WIDTH) { ScriptLibrary.remove(meta) }
                }
                itemTooltip("In your library. Click to remove it.")
            } else {
                pushStyleColor(ImGuiCol.Button, ImGuiColors.ACCENT_PRIMARY)
                pushStyleColor(ImGuiCol.ButtonHovered, ImGuiColors.ACCENT_PRIMARY_HOVER)
                pushStyleColor(ImGuiCol.ButtonActive, ImGuiColors.ACCENT_PRIMARY_ACTIVE)
                button("Add##store_add_$id", width = ACTION_BUTTON_WIDTH, textColor = ImGuiColors.WHITE) { ScriptLibrary.add(meta) }
                popStyleColor(3)
                itemTooltip("Free. Adds it to your library in the Scripts tab.")
            }
        }

        if (expanded) {
            indent()
            styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                text("Version ${meta.version} · Free")
                textWrapped(meta.description.ifBlank { "No description." })
            }
            if (inLibrary) {
                smallButton("Open in your library##store_open_$id") {
                    UIState.scriptSearchText.value = meta.name
                    UIState.selectedTab = UI.Tab.SCRIPTS
                }
            }
            unindent()
        }
    }

    private fun filtered(): List<ScriptMetadata> {
        val query = UIState.storeSearchText.value.trim().lowercase()
        val source = UIState.storeSourceIndex.value
        val scripts = ScriptExecutor.scripts.hashCode()
        cached?.let { if (query == lastQuery && source == lastSource && scripts == lastScripts) return it }

        val wanted = ScriptSource.entries.getOrNull(source - 1)
        val result = ScriptExecutor.scripts.values
            .filter { meta ->
                (wanted == null || ScriptLibrary.sourceOf(meta) == wanted) &&
                    (query.isEmpty() || meta.name.lowercase().contains(query) ||
                        meta.author.lowercase().contains(query) || meta.description.lowercase().contains(query))
            }
            .sortedBy { it.name.lowercase() }

        cached = result
        lastQuery = query
        lastSource = source
        lastScripts = scripts
        return result
    }
}
