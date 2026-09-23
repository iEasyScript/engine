package com.projectx.ui.compose.library

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectx.ui.ScriptActions
import com.projectx.ui.compose.OverlayClock
import com.projectx.ui.compose.OverlayNavigation
import com.projectx.ui.compose.Page
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.GlyphIcon
import com.projectx.ui.compose.components.IconButton
import com.projectx.ui.compose.components.Monogram
import com.projectx.ui.compose.components.Pill
import com.projectx.ui.compose.components.SearchField
import com.projectx.ui.compose.components.Segmented
import com.projectx.ui.compose.components.animatedColor
import com.projectx.ui.compose.components.overlayScrollbarStyle
import com.projectx.ui.compose.components.press
import com.projectx.ui.compose.components.rememberHover
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette
import com.projectx.ui.compose.theme.tint

@Composable
fun LibraryScreen() {
    val entries = LibraryModel.entries
    val visible = LibraryModel.visible(entries, LibraryModel.search.text, LibraryModel.filter)
    val selected = entries.firstOrNull { it.id == LibraryModel.selectedId } ?: visible.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        Toolbar()
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.width(380.dp).fillMaxHeight()) {
                if (entries.isEmpty()) EmptyLibrary() else ScriptList(visible, selected?.id)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.surface)
                    .border(1.dp, Palette.line, RoundedCornerShape(12.dp)),
            ) {
                if (selected != null) ScriptDetail(selected) else NothingSelected()
            }
        }
    }
}

@Composable
private fun Toolbar() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SearchField(LibraryModel.search, "Search your library", width = 260.dp)
        Segmented(LibraryFilter.entries, LibraryModel.filter, { LibraryModel.filter = it }) { it.label }
        Spacer(Modifier.weight(1f))
        IconButton(Glyph.Refresh, { ScriptActions.reload() }, size = 34.dp)
        if (LibraryModel.runningCount > 0) {
            ActionButton("Stop all", { ScriptActions.stopAll() }, tone = ButtonTone.Danger, icon = Glyph.Stop, height = 34.dp)
        }
    }
}

@Composable
private fun ScriptList(visible: List<LibraryEntry>, selectedId: String?) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        if (visible.isEmpty()) {
            BasicText(
                "Nothing in your library matches.",
                style = LocalType.current.body,
                modifier = Modifier.padding(top = 18.dp, start = 4.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(visible, key = { it.id }) { entry -> ScriptRow(entry, entry.id == selectedId) }
        }
        CompositionLocalProvider(LocalScrollbarStyle provides overlayScrollbarStyle()) {
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun ScriptRow(entry: LibraryEntry, selected: Boolean) {
    val hover = rememberHover()
    val type = LocalType.current
    val background = when {
        selected -> Palette.raised
        hover.hovered -> Palette.surface
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(animatedColor(background))
            .border(1.dp, if (selected) Palette.lineStrong else Color.Transparent, RoundedCornerShape(10.dp))
            .press(hover) { LibraryModel.selectedId = entry.id }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Monogram(entry.meta.name, entry.category.tint(), 34.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(entry.meta.name, style = type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText("${entry.meta.author} · v${entry.meta.version}", style = type.dataSmall, maxLines = 1)
        }
        when {
            entry.running -> Pill(runtime(entry.startedAt), Palette.running, dot = true)
            entry.favorite -> GlyphIcon(Glyph.StarFilled, Palette.amber, 12.dp)
        }
    }
}

@Composable
private fun ScriptDetail(entry: LibraryEntry) {
    val type = LocalType.current
    val meta = entry.meta
    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Monogram(meta.name, entry.category.tint(), 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                BasicText(meta.name, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("by ${meta.author} · v${meta.version}", style = type.data)
            }
            IconButton(
                if (entry.favorite) Glyph.StarFilled else Glyph.Star,
                { ScriptActions.toggleFavorite(meta) },
                tint = if (entry.favorite) Palette.amber else Palette.faint,
                activeTint = Palette.amber,
                size = 32.dp,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(entry.category.readableName, entry.category.tint())
            Pill(entry.source.label, Palette.muted)
            if (entry.running) Pill("Running", Palette.running, dot = true)
        }
        Spacer(Modifier.height(18.dp))
        val configurable = ScriptActions.isConfigurable(meta)
        val tab = if (configurable) LibraryModel.detailTab else DetailTab.Overview
        if (configurable) {
            Segmented(DetailTab.entries, tab, { LibraryModel.detailTab = it }) { it.label }
            Spacer(Modifier.height(16.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                DetailTab.Overview -> Column {
                    BasicText("ABOUT", style = type.eyebrow)
                    Spacer(Modifier.height(6.dp))
                    BasicText(meta.description.ifBlank { "No description." }, style = type.body, modifier = Modifier.fillMaxWidth(0.94f))
                }
                DetailTab.Settings -> {
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp)) { ScriptSettingsView(meta) }
                    CompositionLocalProvider(LocalScrollbarStyle provides overlayScrollbarStyle()) {
                        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DetailActions(entry)
    }
}

@Composable
private fun DetailActions(entry: LibraryEntry) {
    val type = LocalType.current
    val meta = entry.meta
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.line))
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (entry.running) {
            Column(Modifier.padding(end = 6.dp)) {
                BasicText("RUNNING FOR", style = type.eyebrow)
                BasicText(runtime(entry.startedAt), style = type.data.copy(color = Palette.running, fontSize = LocalType.current.heading.fontSize))
            }
            ActionButton("Stop", { ScriptActions.stop(meta) }, tone = ButtonTone.Danger, icon = Glyph.Stop, height = 42.dp, modifier = Modifier.width(130.dp))
        } else {
            ActionButton("Start script", { ScriptActions.start(meta) }, tone = ButtonTone.Primary, icon = Glyph.Play, height = 42.dp, modifier = Modifier.width(170.dp))
        }
        Spacer(Modifier.weight(1f))
        if (!entry.running) {
            TextLink("Remove from library") {
                ScriptActions.removeFromLibrary(meta)
                LibraryModel.selectedId = null
            }
        }
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    val hover = rememberHover()
    BasicText(
        label,
        style = LocalType.current.label.copy(color = if (hover.hovered) Palette.stop else Palette.faint),
        modifier = Modifier.press(hover, onClick).padding(6.dp),
    )
}

@Composable
private fun EmptyLibrary() {
    val type = LocalType.current
    Column(Modifier.padding(top = 24.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText("Your library is empty", style = type.heading)
        BasicText("Add the scripts you want from the Store and they will be listed here.", style = type.body)
        ActionButton("Open the Store", { OverlayNavigation.open(Page.Store) }, height = 34.dp)
    }
}

@Composable
private fun NothingSelected() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText("Pick a script to see what it does.", style = LocalType.current.body)
    }
}

/** Reads the shared clock, so a running script's timer recomposes once a second rather than every frame. */
@Composable
private fun runtime(startedAt: Long?): String {
    val now = OverlayClock.nowSeconds
    val seconds = if (startedAt == null) 0 else (now - startedAt / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}


