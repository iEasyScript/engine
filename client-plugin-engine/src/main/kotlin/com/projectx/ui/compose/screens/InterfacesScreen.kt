package com.projectx.ui.compose.screens

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.interfaces.ComponentVisibility
import com.projectx.game.nxt.interfaces.InspectedComponent
import com.projectx.game.nxt.interfaces.InterfaceInspector
import com.projectx.game.nxt.interfaces.InterfacePick
import com.projectx.game.nxt.interfaces.InterfaceScripts
import com.projectx.ui.UIState
import com.projectx.ui.backend.native.NativeBridge
import com.projectx.ui.compose.Feed
import com.projectx.ui.compose.GameThread
import com.projectx.ui.compose.components.ActionButton
import com.projectx.ui.compose.components.ButtonTone
import com.projectx.ui.compose.components.Card
import com.projectx.ui.compose.components.EmptyState
import com.projectx.ui.compose.components.Glyph
import com.projectx.ui.compose.components.GlyphIcon
import com.projectx.ui.compose.components.Hint
import com.projectx.ui.compose.components.Readout
import com.projectx.ui.compose.components.ScreenScroll
import com.projectx.ui.compose.components.SearchField
import com.projectx.ui.compose.components.ToggleChip
import com.projectx.ui.compose.components.animatedColor
import com.projectx.ui.compose.components.overlayScrollbarStyle
import com.projectx.ui.compose.components.press
import com.projectx.ui.compose.components.rememberHover
import com.projectx.ui.compose.theme.LocalType
import com.projectx.ui.compose.theme.Palette
import world.gregs.voidps.gameval.Gameval

data class TreeRow(
    val key: Long,
    val depth: Int,
    val interfaceId: Int,
    val componentId: Int?,
    val title: String,
    val detail: String,
    val visibility: ComponentVisibility?,
    val expandable: Boolean,
    val expanded: Boolean,
    val selected: Boolean,
)

data class Crumb(val componentId: Int, val name: String)

data class SlotRow(val index: Int, val label: String, val visibility: ComponentVisibility?)

data class ComponentDetails(
    val interfaceName: String,
    val label: String,
    val crumbs: List<Crumb>,
    val name: String,
    val idText: String,
    val visibility: ComponentVisibility,
    val summary: List<Pair<String, String>>,
    val fullText: String?,
    val slotIndex: Int,
    val slots: List<SlotRow>,
    val ownHooks: List<Pair<String, String>>,
    val otherHooks: Int,
    val hasScripts: Boolean,
    val technical: List<Pair<String, String>>,
)

data class InterfacesSnapshot(
    val loggedIn: Boolean,
    val picking: Boolean,
    val rows: List<TreeRow>,
    val searchMissed: Boolean,
    val details: ComponentDetails?,
    val selectionGone: Boolean,
    val focusKey: Long?,
)

object InterfacesModel {
    private const val MAX_ROWS = 800
    private const val MAX_DEPTH = 12
    private const val MIN_SEARCH = 2
    private const val ROOT_LAYER = -1
    private const val NO_SLOT = -1
    private const val MAX_ITEM_ID = 200_000
    private const val MIN_REPORTABLE_TEXT = 4
    private const val BREADCRUMB_DEPTH = 8

    var copyStatus by mutableStateOf("")
    private var focusKey: Long? = null

    val feed = Feed(250) { produce() }

    fun togglePicking() = GameThread.post { InterfacePick.active = !InterfacePick.active; feed.invalidate() }

    fun select(interfaceId: Int, componentId: Int) {
        UIState.interfaceDebugInterfaceId.value = interfaceId
        UIState.interfaceDebugComponentId.value = componentId
        UIState.interfaceDebugSlotIndex.value = NO_SLOT
        feed.invalidate()
    }

    fun selectSlot(index: Int) {
        UIState.interfaceDebugSlotIndex.value = index
        feed.invalidate()
    }

    fun toggle(row: TreeRow) = GameThread.post {
        if (row.componentId == null) {
            if (!UIState.interfaceDebugExpanded.remove(row.interfaceId)) UIState.interfaceDebugExpanded.add(row.interfaceId)
        } else {
            val key = layerKey(row.interfaceId, row.componentId)
            if (!UIState.interfaceDebugExpandedLayers.remove(key)) UIState.interfaceDebugExpandedLayers.add(key)
        }
        feed.invalidate()
    }

    fun openInterface(interfaceId: Int) = GameThread.post {
        UIState.interfaceDebugExpanded.add(interfaceId)
        select(interfaceId, 0)
    }

    /** Copies through ImGui's platform backend; the engine JVM is headless, so AWT's clipboard is not there. */
    fun copy(text: String) {
        copyStatus = if (!NativeBridge.clipboardSupported) "Copy needs a rebuilt bootstrap"
        else runCatching { NativeBridge.setClipboardText(text) }.fold({ "Copied" }, { "Copy failed: ${it.message}" })
    }

    fun consumeFocus(): Long? = focusKey.also { focusKey = null }

    private fun produce(): InterfacesSnapshot {
        val loggedIn = runCatching { Bootstrap.client.mainState == MainState.LOGGED_IN }.getOrDefault(false)
        if (!loggedIn) {
            InterfacePick.cancel()
            return InterfacesSnapshot(false, false, emptyList(), false, null, false, null)
        }
        InterfacePick.drain()?.let { picked ->
            // A pick lands on the node it found: its interface opens, any filter that would hide it clears, and
            // the tree scrolls to it once.
            UIState.interfaceDebugInterfaceId.value = picked.interfaceId
            UIState.interfaceDebugComponentId.value = picked.componentId
            UIState.interfaceDebugSlotIndex.value = NO_SLOT
            UIState.interfaceDebugExpanded.add(picked.interfaceId)
            expandAncestors(picked.interfaceId, picked.componentId)
            UIState.interfaceDebugTextFilter.value = ""
            focusKey = layerKey(picked.interfaceId, picked.componentId)
        }
        val (rows, missed) = tree()
        val interfaceId = UIState.interfaceDebugInterfaceId.value
        val componentId = UIState.interfaceDebugComponentId.value.coerceAtLeast(0)
        val slotIndex = UIState.interfaceDebugSlotIndex.value
        val selected = if (slotIndex >= 0) InterfaceInspector.slotChild(interfaceId, componentId, slotIndex)
        else InterfaceInspector.component(interfaceId, componentId)
        return InterfacesSnapshot(
            loggedIn = true,
            picking = InterfacePick.active,
            rows = rows,
            searchMissed = missed,
            details = selected?.let { details(it, slotIndex) },
            selectionGone = selected == null && slotIndex >= 0,
            focusKey = focusKey,
        )
    }

    private fun tree(): Pair<List<TreeRow>, Boolean> {
        val filter = UIState.interfaceDebugTextFilter.value.trim().lowercase()
        val showHidden = UIState.interfaceDebugShowHidden.value
        val searching = filter.length >= MIN_SEARCH
        val rows = mutableListOf<TreeRow>()
        val selectedInterface = UIState.interfaceDebugInterfaceId.value
        val selectedComponent = UIState.interfaceDebugComponentId.value

        for (interfaceId in InterfaceInspector.loadedInterfaces()) {
            if (rows.size >= MAX_ROWS) break
            val name = runCatching { Gameval.interfaceLabel(interfaceId) }.getOrDefault("$interfaceId")
            val nameMatches = !searching || name.lowercase().contains(filter) || interfaceId.toString() == filter
            val all = InterfaceInspector.components(interfaceId).filter { showHidden || it.visibility != ComponentVisibility.HIDDEN }
            val hits = if (nameMatches) emptyList() else all.filter { matches(it, filter) }
            if (!nameMatches && hits.isEmpty()) continue

            val open = interfaceId in UIState.interfaceDebugExpanded || hits.isNotEmpty()
            rows += TreeRow(
                key = interfaceId.toLong() shl 32 or 0xFFFFFFFFL, depth = 0, interfaceId = interfaceId, componentId = null,
                title = name, detail = "${InterfaceInspector.componentCount(interfaceId)}", visibility = null,
                expandable = true, expanded = open, selected = interfaceId == selectedInterface,
            )
            if (!open) continue
            if (hits.isNotEmpty()) {
                hits.take(MAX_ROWS).forEach { rows += componentRow(it, 1, 0, false, selectedInterface, selectedComponent) }
            } else {
                val byLayer = all.groupBy { runCatching { it.component.parentLayerId }.getOrDefault(ROOT_LAYER) }
                layer(rows, interfaceId, byLayer, ROOT_LAYER, 1, selectedInterface, selectedComponent)
            }
        }
        return rows.take(MAX_ROWS) to (searching && rows.isEmpty())
    }

    private fun layer(
        rows: MutableList<TreeRow>, interfaceId: Int, byLayer: Map<Int, List<InspectedComponent>>, layerId: Int, depth: Int,
        selectedInterface: Int, selectedComponent: Int,
    ) {
        if (depth > MAX_DEPTH) return
        for (entry in byLayer[layerId].orEmpty()) {
            if (rows.size >= MAX_ROWS) return
            val children = byLayer[entry.componentId].orEmpty()
            val open = layerKey(interfaceId, entry.componentId) in UIState.interfaceDebugExpandedLayers
            rows += componentRow(entry, depth, children.size, open, selectedInterface, selectedComponent)
            if (open && children.isNotEmpty()) layer(rows, interfaceId, byLayer, entry.componentId, depth + 1, selectedInterface, selectedComponent)
        }
    }

    private fun componentRow(entry: InspectedComponent, depth: Int, children: Int, open: Boolean, selectedInterface: Int, selectedComponent: Int): TreeRow {
        val name = componentName(entry.interfaceId, entry.componentId)
        val preview = displayText(runCatching { entry.component.text }.getOrDefault("")).take(28)
        val item = heldItem(runCatching { entry.component.itemId }.getOrDefault(0))
        val detail = buildString {
            if (children > 0) append("$children  ")
            if (preview.isNotBlank()) append("\"$preview\"  ")
            if (item != null) append("item $item")
        }.trim()
        return TreeRow(
            key = layerKey(entry.interfaceId, entry.componentId), depth = depth, interfaceId = entry.interfaceId,
            componentId = entry.componentId, title = name, detail = detail, visibility = entry.visibility,
            expandable = children > 0, expanded = open,
            selected = entry.interfaceId == selectedInterface && entry.componentId == selectedComponent,
        )
    }

    private fun details(entry: InspectedComponent, slotIndex: Int): ComponentDetails {
        val comp = entry.component
        val raw = runCatching { comp.text }.getOrDefault("")
        val body = displayText(raw)
        val item = heldItem(runCatching { comp.itemId }.getOrDefault(0))
        val summary = buildList {
            if (body.isNotBlank()) add("Text" to body.take(120))
            else if (raw.length >= MIN_REPORTABLE_TEXT) add("Text" to "(${raw.length} bytes, not readable)")
            if (item != null) {
                val stack = runCatching { comp.stackSize }.getOrDefault(0)
                val itemName = runCatching { Gameval.objLabel(item) }.getOrDefault("$item")
                add("Holds item" to if (stack > 1) "$itemName x$stack" else itemName)
            }
            entry.rect?.let { add("On screen at" to "${it.x}, ${it.y}   (${it.width} x ${it.height})") }
        }
        val crumbs = ArrayDeque<Crumb>()
        var walker = runCatching { comp.parent }.getOrNull()
        var guard = 0
        while (walker != null && guard++ < BREADCRUMB_DEPTH) {
            val id = runCatching { walker.componentId }.getOrDefault(-1)
            if (id < 0) break
            crumbs.addFirst(Crumb(id, componentName(entry.interfaceId, id)))
            walker = runCatching { walker.parent }.getOrNull()
        }
        val slots = if (slotIndex >= 0) emptyList() else runCatching { comp.slotChildren }.getOrDefault(emptyList()).mapIndexed { index, child ->
            val described = runCatching { InterfaceInspector.describe(entry.interfaceId, child) }.getOrNull()
            val preview = displayText(runCatching { child.text }.getOrDefault("")).take(40)
            val childItem = heldItem(runCatching { child.itemId }.getOrDefault(0))
            val label = buildString {
                append("slot $index")
                if (preview.isNotBlank()) append("  \"$preview\"")
                if (childItem != null) append("  ").append(runCatching { Gameval.objLabel(childItem) }.getOrDefault("item $childItem"))
            }
            SlotRow(index, label, described?.visibility)
        }
        val own = runCatching { InterfaceScripts.forComponent(entry.interfaceId, entry.componentId) }.getOrDefault(emptyList())
        val all = runCatching { InterfaceScripts.forInterface(entry.interfaceId) }.getOrDefault(emptyList())
        val technical = buildList {
            add("Interface id" to "${entry.interfaceId}")
            add("Component id" to "${entry.componentId}")
            add("Parent layer" to runCatching { "${comp.parentLayerId}" }.getOrDefault("?"))
            add("Slot id" to runCatching { "${comp.slotId}" }.getOrDefault("?"))
            add("Parent-local xy" to runCatching { "${comp.parentRelX}, ${comp.parentRelY}" }.getOrDefault("?"))
            add("Layout size" to runCatching { "${comp.screenWidth} x ${comp.screenHeight}" }.getOrDefault("?"))
            val graphic = runCatching { comp.graphicId }.getOrDefault(0)
            if (graphic != 0) add("Graphic id" to "$graphic")
        }
        return ComponentDetails(
            interfaceName = runCatching { Gameval.interfaceLabel(entry.interfaceId) }.getOrDefault("${entry.interfaceId}"),
            label = runCatching { Gameval.componentLabel(entry.interfaceId, entry.componentId) }.getOrDefault("${entry.interfaceId}:${entry.componentId}"),
            crumbs = crumbs.toList(),
            name = componentName(entry.interfaceId, entry.componentId),
            idText = "${entry.interfaceId}:${entry.componentId}",
            visibility = entry.visibility,
            summary = summary,
            fullText = raw.takeIf { body.length > 120 },
            slotIndex = slotIndex,
            slots = slots,
            ownHooks = own.map { it.trigger to ("script ${it.scriptId}" + if (it.args.isEmpty()) "" else "  args: ${it.args.joinToString(", ")}") },
            otherHooks = all.size - own.size,
            hasScripts = all.isNotEmpty(),
            technical = technical,
        )
    }

    private fun matches(entry: InspectedComponent, filter: String): Boolean {
        if (entry.componentId.toString() == filter) return true
        if (componentName(entry.interfaceId, entry.componentId).lowercase().contains(filter)) return true
        val body = displayText(runCatching { entry.component.text }.getOrDefault(""))
        return body.isNotEmpty() && body.lowercase().contains(filter)
    }

    private fun expandAncestors(interfaceId: Int, componentId: Int) {
        var current = InterfaceInspector.component(interfaceId, componentId) ?: return
        var guard = 0
        while (guard++ < MAX_DEPTH) {
            val layerId = runCatching { current.component.parentLayerId }.getOrDefault(ROOT_LAYER)
            if (layerId == ROOT_LAYER) return
            UIState.interfaceDebugExpandedLayers.add(layerKey(interfaceId, layerId))
            current = InterfaceInspector.component(interfaceId, layerId) ?: return
        }
    }

    private fun componentName(interfaceId: Int, componentId: Int): String =
        runCatching { Gameval.component(interfaceId, componentId)?.substringAfter(':') }.getOrNull() ?: "$componentId"

    /** Component text is read from memory that is not always a string; mostly-unprintable content is treated as none. */
    private fun displayText(raw: String): String {
        if (raw.isEmpty()) return ""
        val printable = raw.filter { it.code in 0x20..0x7E }
        return if (printable.length * 2 < raw.length) "" else printable
    }

    /** Components holding no item leave a sentinel or a stale value here; only a real item id is worth showing. */
    private fun heldItem(id: Int): Int? = id.takeIf { it in 1..MAX_ITEM_ID }

    fun layerKey(interfaceId: Int, componentId: Int): Long = (interfaceId.toLong() shl 32) or (componentId.toLong() and 0xFFFFFFFFL)
}

private fun ComponentVisibility.color() = when (this) {
    ComponentVisibility.DRAWN -> Palette.running
    ComponentVisibility.LAID_OUT -> Palette.amber
    ComponentVisibility.HIDDEN -> Palette.faint
}

private fun ComponentVisibility.describe() = when (this) {
    ComponentVisibility.DRAWN -> "Showing on screen right now"
    ComponentVisibility.LAID_OUT -> "Loaded, but not drawn"
    ComponentVisibility.HIDDEN -> "Hidden, nothing is drawn for this"
}

@Composable
fun InterfacesScreen() {
    val s = InterfacesModel.feed.value ?: return
    if (!s.loggedIn) {
        EmptyState("Log in to inspect interfaces", "Interface Debug reads the screens the client currently holds.")
        return
    }
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(
                if (s.picking) "Cancel" else "Inspect element",
                { InterfacesModel.togglePicking() },
                tone = if (s.picking) ButtonTone.Danger else ButtonTone.Primary,
                icon = Glyph.Crosshair,
            )
            Hint(if (s.picking) "Click anything in the game. Esc cancels." else "Or pick from the screens below.")
            Spacer(Modifier.weight(1f))
            SearchField(UIState.interfaceDebugTextFilter, "Name or on-screen text", 220.dp)
            ToggleChip("Hidden parts", UIState.interfaceDebugShowHidden.value) { UIState.interfaceDebugShowHidden.value = it; InterfacesModel.feed.invalidate() }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tree(s, Modifier.width(400.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(12.dp))) {
                when {
                    s.selectionGone -> EmptyState("That part is gone", "It was torn down since it was selected.") {
                        ActionButton("Back to the container", { InterfacesModel.selectSlot(-1) })
                    }
                    s.details == null -> EmptyState("Nothing selected", "Pick a screen or part on the left, or inspect one in the game.")
                    else -> Details(s.details)
                }
            }
        }
    }
}

@Composable
private fun Tree(s: InterfacesSnapshot, modifier: Modifier) {
    val state = rememberLazyListState()
    val focus = s.focusKey
    LaunchedEffect(focus) {
        if (focus != null) {
            val index = s.rows.indexOfFirst { it.key == focus }
            if (index >= 0) state.scrollToItem((index - 4).coerceAtLeast(0))
            InterfacesModel.consumeFocus()
        }
    }
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(12.dp))) {
        Box(Modifier.weight(1f)) {
            if (s.searchMissed) Hint("Nothing matches. Only screens the client holds are searched.", Modifier.padding(14.dp))
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(6.dp)) {
                items(s.rows, key = { it.key }) { TreeLine(it) }
            }
            CompositionLocalProvider(LocalScrollbarStyle provides overlayScrollbarStyle()) {
                VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
            }
        }
        Row(Modifier.fillMaxWidth().background(Palette.raised).padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ComponentVisibility.entries.forEach { v -> Legend(v) }
        }
    }
}

@Composable
private fun Legend(visibility: ComponentVisibility) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(visibility.color()))
        BasicText(
            when (visibility) {
                ComponentVisibility.DRAWN -> "On screen"
                ComponentVisibility.LAID_OUT -> "Not drawn"
                ComponentVisibility.HIDDEN -> "Hidden"
            },
            style = LocalType.current.dataSmall,
        )
    }
}

@Composable
private fun TreeLine(row: TreeRow) {
    val hover = rememberHover()
    val type = LocalType.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(animatedColor(if (row.selected) Palette.hover else if (hover.hovered) Palette.raised else Color.Transparent))
            .press(hover) {
                if (row.componentId == null) InterfacesModel.openInterface(row.interfaceId) else InterfacesModel.select(row.interfaceId, row.componentId)
            }
            .padding(start = (6 + row.depth * 14).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (row.expandable) {
                val toggleHover = rememberHover()
                Box(Modifier.size(16.dp).press(toggleHover) { InterfacesModel.toggle(row) }, contentAlignment = Alignment.Center) {
                    GlyphIcon(if (row.expanded) Glyph.ChevronDown else Glyph.ChevronRight, if (toggleHover.hovered) Palette.text else Palette.muted, 9.dp)
                }
            }
        }
        if (row.visibility != null) Box(Modifier.size(6.dp).clip(CircleShape).background(row.visibility.color()))
        BasicText(
            row.title,
            style = (if (row.componentId == null) type.bodyStrong else type.label).copy(
                color = when {
                    row.selected -> Palette.amber
                    row.visibility == ComponentVisibility.HIDDEN -> Palette.faint
                    else -> Palette.text
                },
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.detail.isNotBlank()) BasicText(row.detail, style = type.dataSmall.copy(color = Palette.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(d: ComponentDetails) {
    val type = LocalType.current
    ScreenScroll(Modifier.padding(top = 16.dp)) {
        FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(d.interfaceName, style = type.dataSmall)
            d.crumbs.forEach { crumb ->
                BasicText("/", style = type.dataSmall.copy(color = Palette.faint))
                val hover = rememberHover()
                BasicText(
                    crumb.name,
                    style = type.dataSmall.copy(color = if (hover.hovered) Palette.text else Palette.muted),
                    modifier = Modifier.press(hover) { InterfacesModel.select(d.idText.substringBefore(':').toInt(), crumb.componentId) },
                )
            }
            BasicText("/", style = type.dataSmall.copy(color = Palette.faint))
            BasicText(d.name, style = type.dataSmall.copy(color = Palette.amber))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(d.label, style = type.title.copy(fontSize = type.heading.fontSize * 1.25f), maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(d.visibility.color()))
                    BasicText(d.visibility.describe(), style = type.label.copy(color = d.visibility.color()))
                }
            }
            ActionButton("Copy id", { InterfacesModel.copy(d.idText) }, icon = Glyph.Copy, height = 32.dp)
        }
        if (InterfacesModel.copyStatus.isNotEmpty()) Hint(InterfacesModel.copyStatus)
        if (d.slotIndex >= 0) {
            Card {
                Hint("Part ${d.slotIndex} inside this component, one of several copies of the same template.")
                ActionButton("Back to the container", { InterfacesModel.selectSlot(-1) }, height = 30.dp)
            }
        }
        if (d.summary.isNotEmpty()) Card { Readout(d.summary) }
        d.fullText?.let { ActionButton("Copy full text", { InterfacesModel.copy(it) }, icon = Glyph.Copy, height = 30.dp) }
        if (d.slots.isNotEmpty()) {
            Card {
                BasicText("Contains ${d.slots.size} part(s)", style = type.heading)
                Column(Modifier.fillMaxWidth()) {
                    d.slots.take(300).forEach { slot ->
                        val hover = rememberHover()
                        Row(
                            Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (hover.hovered) Palette.raised else Color.Transparent)
                                .press(hover) { InterfacesModel.selectSlot(slot.index) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            slot.visibility?.let { Box(Modifier.size(6.dp).clip(CircleShape).background(it.color())) }
                            BasicText(slot.label, style = type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (d.slots.size > 300) Hint("${d.slots.size - 300} more not listed.")
                }
            }
        }
        if (d.hasScripts) {
            Card {
                BasicText("Scripts", style = type.heading)
                if (d.ownHooks.isEmpty()) Hint("This part runs no scripts of its own.") else Readout(d.ownHooks)
                if (d.otherHooks > 0) Hint("${d.otherHooks} more script hook(s) elsewhere in this screen.")
            }
        }
        Card {
            BasicText("Technical details", style = type.heading)
            Readout(d.technical)
        }
    }
}
