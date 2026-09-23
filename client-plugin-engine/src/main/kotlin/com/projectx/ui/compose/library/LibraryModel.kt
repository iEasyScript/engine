package com.projectx.ui.compose.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.ui.ScriptLibrary
import com.projectx.ui.ScriptSource
import com.projectx.ui.UIState
import com.projectx.ui.compose.OverlayText

data class LibraryEntry(
    val meta: ScriptMetadata,
    val category: ScriptCategory,
    val source: ScriptSource,
    val running: Boolean,
    val favorite: Boolean,
    val startedAt: Long?,
) {
    val id: String get() = meta.scriptClass.name
}

enum class LibraryFilter(val label: String) { All("All"), Running("Running"), Favorites("Starred") }

enum class DetailTab(val label: String) { Overview("Overview"), Settings("Settings") }

/**
 * The library as the interface sees it.
 *
 * Rebuilt on the main-logic thread, where script state changes, and published as Compose state so the render
 * thread only ever reads a finished list. Publishing only on change keeps an idle panel from recomposing.
 */
object LibraryModel {
    var entries by mutableStateOf(emptyList<LibraryEntry>())
        internal set

    var selectedId by mutableStateOf<String?>(null)
    var filter by mutableStateOf(LibraryFilter.All)
    var detailTab by mutableStateOf(DetailTab.Overview)
    val search = OverlayText(maxLength = 40)

    private val categories = mutableMapOf<String, ScriptCategory>()

    val runningCount: Int get() = entries.count { it.running }

    fun refresh() {
        val favorites = UIState.favoriteScripts
        val next = ScriptExecutor.scripts.values
            .filter { ScriptLibrary.contains(it) || ScriptExecutor.isScriptRunning(it.scriptClass) }
            .map { meta ->
                LibraryEntry(
                    meta = meta,
                    category = categoryOf(meta),
                    source = ScriptLibrary.sourceOf(meta),
                    running = ScriptExecutor.isScriptRunning(meta.scriptClass),
                    favorite = meta.scriptClass in favorites,
                    startedAt = ScriptExecutor.startedAt(meta.scriptClass),
                )
            }
            .sortedWith(compareByDescending<LibraryEntry> { it.running }.thenByDescending { it.favorite }.thenBy { it.meta.name.lowercase() })
        if (next != entries) entries = next
    }

    fun visible(entries: List<LibraryEntry>, query: String, filter: LibraryFilter): List<LibraryEntry> {
        val q = query.trim().lowercase()
        return entries.filter { entry ->
            val matchesFilter = when (filter) {
                LibraryFilter.All -> true
                LibraryFilter.Running -> entry.running
                LibraryFilter.Favorites -> entry.favorite
            }
            matchesFilter && (q.isEmpty() || entry.meta.name.lowercase().contains(q) || entry.meta.author.lowercase().contains(q))
        }
    }

    private fun categoryOf(meta: ScriptMetadata): ScriptCategory = categories.getOrPut(meta.scriptClass.name) {
        meta.scriptClass.getAnnotation(ScriptDescription::class.java)?.category ?: ScriptCategory.OTHER
    }
}
