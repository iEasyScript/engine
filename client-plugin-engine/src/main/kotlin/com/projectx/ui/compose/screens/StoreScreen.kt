package com.projectx.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.ScriptExecutor
import com.projectx.script.ScriptMetadata
import com.projectx.ui.ScriptLibrary
import com.projectx.ui.ScriptSource
import com.projectx.ui.UIState
import com.projectx.ui.compose.Feed
import com.projectx.ui.compose.GameThread
import com.projectx.ui.compose.OverlayNavigation
import com.projectx.ui.compose.Page
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.components.Card
import com.projectx.ui.compose.components.Dropdown
import com.projectx.ui.compose.components.EmptyState
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.Monogram
import com.projectx.ui.compose.components.ScreenScroll
import com.projectx.ui.compose.components.SearchField
import com.projectx.ui.compose.library.LibraryModel
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.tint

data class StoreEntry(val meta: ScriptMetadata, val source: ScriptSource, val category: ScriptCategory, val inLibrary: Boolean)

object StoreModel {
    var source by mutableStateOf<ScriptSource?>(null)

    val feed = Feed(500) {
        ScriptExecutor.scripts.values.map { meta ->
            StoreEntry(
                meta,
                ScriptLibrary.sourceOf(meta),
                meta.scriptClass.getAnnotation(ScriptDescription::class.java)?.category ?: ScriptCategory.OTHER,
                ScriptLibrary.contains(meta),
            )
        }.sortedBy { it.meta.name.lowercase() }
    }

    fun visible(all: List<StoreEntry>): List<StoreEntry> {
        val q = UIState.storeSearchText.value.trim().lowercase()
        return all.filter { entry ->
            (source == null || entry.source == source) &&
                (q.isEmpty() || entry.meta.name.lowercase().contains(q) || entry.meta.author.lowercase().contains(q) || entry.meta.description.lowercase().contains(q))
        }
    }

    fun toggle(entry: StoreEntry) = GameThread.post {
        if (entry.inLibrary) ScriptLibrary.remove(entry.meta) else ScriptLibrary.add(entry.meta)
        feed.invalidate()
    }
}

@Composable
fun StoreScreen() {
    val all = StoreModel.feed.value.orEmpty()
    val shown = StoreModel.visible(all)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchField(UIState.storeSearchText, "Search every script", 260.dp)
            Dropdown(StoreModel.source, listOf(null) + ScriptSource.entries, { StoreModel.source = it }, width = 170.dp) { it?.label ?: "All sources" }
            Spacer(Modifier.weight(1f))
            Hint("${shown.size} of ${all.size} scripts · every script is free")
        }
        ScreenScroll {
            if (shown.isEmpty()) EmptyState(if (all.isEmpty()) "No scripts are loaded" else "Nothing matches", "Scripts in ~/.projectx/scripts show up here.")
            shown.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { StoreCard(it, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StoreCard(entry: StoreEntry, modifier: Modifier) {
    val type = LocalType.current
    Card(modifier.height(150.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Monogram(entry.meta.name, entry.category.tint(), 38.dp)
            Column(Modifier.weight(1f)) {
                BasicText(entry.meta.name, style = type.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("${entry.meta.author} · v${entry.meta.version} · ${entry.source.label}", style = type.dataSmall, maxLines = 1)
            }
        }
        BasicText(
            entry.meta.description.ifBlank { "No description." },
            style = type.body.copy(fontSize = type.label.fontSize),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.inLibrary) {
                ActionButton("In your library", { StoreModel.toggle(entry) }, icon = Glyph.Check, height = 30.dp)
                ActionButton("Open", {
                    LibraryModel.selectedId = entry.meta.scriptClass.name
                    OverlayNavigation.open(Page.Library)
                }, height = 30.dp)
            } else {
                ActionButton("Add to library", { StoreModel.toggle(entry) }, tone = ButtonTone.Primary, icon = Glyph.Plus, height = 30.dp)
            }
            Spacer(Modifier.weight(1f))
            BasicText(entry.category.readableName, style = type.dataSmall.copy(color = entry.category.tint()))
        }
    }
}

