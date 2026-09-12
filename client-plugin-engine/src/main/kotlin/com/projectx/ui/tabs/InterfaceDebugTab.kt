package com.projectx.ui.tabs

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.MainState
import com.projectx.game.nxt.interfaces.ComponentVisibility
import com.projectx.game.nxt.interfaces.InspectedComponent
import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.game.nxt.interfaces.InterfaceInspector
import com.projectx.game.nxt.interfaces.InterfacePick
import com.projectx.game.nxt.interfaces.InterfaceScripts
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiChildFlags
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.ui.backend.dsl.utils.ImGuiDir
import com.projectx.ui.backend.native.NativeBridge
import world.gregs.voidps.gameval.Gameval

object InterfaceDebugTab {
    private const val TREE_HEIGHT = 260f

    /** Every row costs live memory reads; an interface with more than this is worth truncating loudly. */
    private const val MAX_ROWS = 500
    private const val SLOT_LIST_HEIGHT = 150f
    private const val BREADCRUMB_DEPTH = 8
    private const val MAX_TREE_DEPTH = 12
    private const val ROOT_LAYER = -1
    private const val NO_SLOT = -1

    /** Searching reads the text of every component, so a one-character query is not worth the scan. */
    private const val MIN_SEARCH_LENGTH = 2

    /** Below this, unreadable content is padding rather than text worth telling the user about. */
    private const val MIN_REPORTABLE_TEXT = 4

    /**
     * Components that hold no item leave this field at a sentinel or at whatever was last there, so only a
     * value inside the real item-id range is worth reporting. Reading -1 as an item is what produced the
     * "large negative number" the panel used to show.
     */
    private fun heldItem(id: Int): Int? = id.takeIf { it in 1..MAX_ITEM_ID }

    private const val MAX_ITEM_ID = 200_000

    /** Set for one frame after a pick so the tree scrolls the new selection into view exactly once. */
    private var focusPending = false

    private fun select(interfaceId: Int, componentId: Int) {
        UIState.interfaceDebugInterfaceId.value = interfaceId
        UIState.interfaceDebugComponentId.value = componentId
        UIState.interfaceDebugSlotIndex.value = NO_SLOT
    }

    /** Selects one slot child of the component already selected, identified by its index. */
    private fun selectSlot(index: Int) {
        UIState.interfaceDebugSlotIndex.value = index
    }

    /**
     * Component text is read from live memory that is not always a string, so it can decode to control bytes
     * and stray high characters that render as noise. Keep the printable ASCII, and treat a run that is
     * mostly unprintable as no text at all rather than showing gibberish.
     */
    private fun displayText(raw: String): String {
        if (raw.isEmpty()) return ""
        val printable = raw.filter { it.code in 0x20..0x7E }
        return if (printable.length * 2 < raw.length) "" else printable
    }

    @Volatile
    private var copyStatus: String = ""

    /**
     * Copies through ImGui's platform backend.
     *
     * AWT is not an option here: the engine's JVM is started headless so the texture loader never opens a
     * display, and `Toolkit.getSystemClipboard()` throws in that mode - which is why the old copy button
     * silently did nothing every time it was pressed.
     */
    private fun copyToClipboard(text: String) {
        if (!NativeBridge.clipboardSupported) {
            copyStatus = "Copy needs a rebuilt bootstrap"
            return
        }
        copyStatus = runCatching { NativeBridge.setClipboardText(text) }
            .fold({ "Copied" }, { "Copy failed: ${it.message}" })
    }

    private fun isClientReady(): Boolean =
        runCatching { Bootstrap.client.mainState == MainState.LOGGED_IN }.getOrDefault(false)

    fun ChildScope.render() {
        if (!isClientReady()) {
            InterfacePick.cancel()
            text("Not logged in - Interface Debug requires login")
            return
        }

        InterfacePick.drain()?.let { picked ->
            select(picked.interfaceId, picked.componentId)
            // Picking off the screen should land you on that node, not leave you to find it: open its
            // interface, drop any filter that would hide it, and scroll it into view once.
            UIState.interfaceDebugExpanded.add(picked.interfaceId)
            expandAncestors(picked.interfaceId, picked.componentId)
            UIState.interfaceDebugTextFilter.value = ""
            focusPending = true
        }

        val picking = InterfacePick.active
        button(if (picking) "Cancel" else "Inspect element") { InterfacePick.active = !picking }
        sameLine()
        styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
            textWrapped(
                if (picking) "Point at anything in the game and click it. Esc cancels."
                else "Click the button, then click any part of the game to see what it is."
            )
        }

        section("Screens")
        properties("ifdebug-tree-opts") {
            row("Search name or text") { inputText("##ifFilter", UIState.interfaceDebugTextFilter) }
            row("Include hidden parts") { checkbox("##ifShowHidden", UIState.interfaceDebugShowHidden) }
        }
        renderTree()
        styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
            textWrapped("Gold = on screen now.  Amber = loaded but not drawn.  Grey = hidden.")
        }

        section("Selected")
        val interfaceId = UIState.interfaceDebugInterfaceId.value
        val componentId = UIState.interfaceDebugComponentId.value.coerceAtLeast(0)
        val slotIndex = UIState.interfaceDebugSlotIndex.value

        // Re-resolved every frame rather than held: a slot child can be torn down between frames, and a
        // retained pointer would then be read as live memory.
        val selected =
            if (slotIndex >= 0) InterfaceInspector.slotChild(interfaceId, componentId, slotIndex)
            else InterfaceInspector.component(interfaceId, componentId)

        // Details scroll inside their own region rather than extending the page. Their height swings with
        // what is selected - a component with scripts and slot children is many times taller than a leaf -
        // and while that rode on the tab's own scrollbar every click resized the page and moved the tree
        // out from under the cursor.
        child("ifdebug-details", height = 0f, childFlags = ImGuiChildFlags.Borders) {
            when {
                selected == null && slotIndex >= 0 -> {
                    text("That part is no longer there.")
                    button("Back to the container") { selectSlot(NO_SLOT) }
                }
                selected == null -> text("Nothing selected.")
                else -> renderDetails(selected, slotIndex)
            }
        }
    }

    private fun ChildScope.renderTree() {
        val filter = UIState.interfaceDebugTextFilter.value.trim().lowercase()
        val showHidden = UIState.interfaceDebugShowHidden.value
        val interfaces = InterfaceInspector.loadedInterfaces()

        child("ifdebug-tree", height = TREE_HEIGHT, childFlags = ImGuiChildFlags.Borders) {
            if (interfaces.isEmpty()) {
                text("No interfaces loaded.")
                return@child
            }
            val searching = filter.length >= MIN_SEARCH_LENGTH
            var anyResult = false

            interfaces.forEach { interfaceId ->
                val name = runCatching { Gameval.interfaceLabel(interfaceId) }.getOrDefault("$interfaceId")
                val nameMatches = !searching ||
                    name.lowercase().contains(filter) ||
                    interfaceId.toString() == filter

                val all = InterfaceInspector.components(interfaceId)
                    .filter { showHidden || it.visibility != ComponentVisibility.HIDDEN }

                // Searching looks inside the interfaces too, matching component names and their on-screen
                // text - an interface-name-only filter cannot find the thing you can actually read.
                val hits = if (nameMatches) emptyList() else all.filter { matches(it, filter) }
                if (!nameMatches && hits.isEmpty()) return@forEach
                anyResult = true

                val count = InterfaceInspector.componentCount(interfaceId)
                // Expansion is driven from our own set rather than ImGui's node state, so a pick can open
                // the interface it landed in. ImGui exposes no way to force a tree node open from here.
                // A search opens its own results, otherwise the matches stay hidden behind a closed arrow.
                val open = interfaceId in UIState.interfaceDebugExpanded || hits.isNotEmpty()
                arrowButton(
                    "ifExpand$interfaceId",
                    if (open) ImGuiDir.Down else ImGuiDir.Right,
                    if (open) "-" else "+",
                ) {
                    if (open) UIState.interfaceDebugExpanded.remove(interfaceId)
                    else UIState.interfaceDebugExpanded.add(interfaceId)
                }
                sameLine()
                val isCurrent = UIState.interfaceDebugInterfaceId.value == interfaceId
                styleColor(ImGuiCol.Text, if (isCurrent) ImGuiColors.ACCENT_PRIMARY else ImGuiColors.TEXT_PRIMARY) {
                    selectable("$name  ($count)###if$interfaceId", isCurrent) {
                        UIState.interfaceDebugExpanded.add(interfaceId)
                        select(interfaceId, 0)
                    }
                }
                if (!open) return@forEach

                indent()
                if (hits.isNotEmpty()) {
                    // Matches are shown flat: they can sit in unrelated layers, and rebuilding the path to
                    // each one would bury the results in the branches they happen to hang off.
                    hits.take(MAX_ROWS).forEach { componentRow(it) }
                    if (hits.size > MAX_ROWS) {
                        styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                            text("... ${hits.size - MAX_ROWS} more matches")
                        }
                    }
                } else {
                    // Grouped by the layer each component sits in, so the tree mirrors how the interface is
                    // actually built. Listing every component flat buries a window's own parts among every
                    // unrelated part of the same interface.
                    val byLayer = all.groupBy {
                        runCatching { it.component.parentLayerId }.getOrDefault(ROOT_LAYER)
                    }
                    renderLayer(interfaceId, byLayer, ROOT_LAYER, depth = 0)
                }
                unindent()
            }

            if (searching && !anyResult) {
                styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                    textWrapped(
                        "Nothing matches \"$filter\". Only screens the client currently holds are searched, " +
                            "and text the game builds at runtime is not stored on the component."
                    )
                }
            }
        }

        // Cleared whether or not the selected row was reached: if its branch is collapsed or filtered away
        // the request cannot be honoured, and leaving it armed fires a scroll at some unrelated later frame.
        focusPending = false
    }

    /** Matches a component by its gameval name, the text it is displaying, or its exact id. */
    private fun matches(entry: InspectedComponent, filter: String): Boolean {
        if (entry.componentId.toString() == filter) return true
        val name = runCatching { Gameval.component(entry.interfaceId, entry.componentId)?.substringAfter(':') }
            .getOrNull()
        if (name != null && name.lowercase().contains(filter)) return true
        val body = displayText(runCatching { entry.component.text }.getOrDefault(""))
        return body.isNotEmpty() && body.lowercase().contains(filter)
    }

    /** Renders one layer's direct children, recursing into any that are layers themselves. */
    private fun ChildScope.renderLayer(
        interfaceId: Int,
        byLayer: Map<Int, List<InspectedComponent>>,
        layerId: Int,
        depth: Int,
    ) {
        if (depth > MAX_TREE_DEPTH) return
        val nodes = byLayer[layerId] ?: return

        nodes.take(MAX_ROWS).forEach { entry ->
            val children = byLayer[entry.componentId].orEmpty()
            val key = layerKey(interfaceId, entry.componentId)
            val open = key in UIState.interfaceDebugExpandedLayers

            if (children.isNotEmpty()) {
                arrowButton(
                    "layer$key",
                    if (open) ImGuiDir.Down else ImGuiDir.Right,
                    if (open) "-" else "+",
                ) {
                    if (open) UIState.interfaceDebugExpandedLayers.remove(key)
                    else UIState.interfaceDebugExpandedLayers.add(key)
                }
                sameLine()
            } else {
                // Keeps leaf labels on the same left edge as the ones that carry a disclosure arrow.
                text("    ")
                sameLine()
            }

            componentRow(entry, children.size)

            if (open && children.isNotEmpty()) {
                indent()
                renderLayer(interfaceId, byLayer, entry.componentId, depth + 1)
                unindent()
            }
        }
        if (nodes.size > MAX_ROWS) {
            styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                text("... ${nodes.size - MAX_ROWS} more not shown")
            }
        }
    }

    /**
     * Opens every layer between the interface root and [componentId].
     *
     * Now that the tree nests, a picked component is usually several collapsed layers deep - without this
     * the selection exists but there is nothing on screen to scroll to.
     */
    private fun expandAncestors(interfaceId: Int, componentId: Int) {
        var current = InterfaceInspector.component(interfaceId, componentId) ?: return
        var guard = 0
        while (guard++ < MAX_TREE_DEPTH) {
            val layerId = runCatching { current.component.parentLayerId }.getOrDefault(ROOT_LAYER)
            if (layerId == ROOT_LAYER) return
            UIState.interfaceDebugExpandedLayers.add(layerKey(interfaceId, layerId))
            current = InterfaceInspector.component(interfaceId, layerId) ?: return
        }
    }

    private fun layerKey(interfaceId: Int, componentId: Int): Long =
        (interfaceId.toLong() shl 32) or (componentId.toLong() and 0xFFFFFFFFL)

    private fun ChildScope.componentRow(entry: InspectedComponent, childCount: Int = 0) {
        val selected = UIState.interfaceDebugInterfaceId.value == entry.interfaceId &&
            UIState.interfaceDebugComponentId.value == entry.componentId

        val name = runCatching { Gameval.component(entry.interfaceId, entry.componentId)?.substringAfter(':') }
            .getOrNull()
        val preview = displayText(runCatching { entry.component.text }.getOrDefault("")).take(24)
        val itemId = heldItem(runCatching { entry.component.itemId }.getOrDefault(0))

        val label = buildString {
            append(badge(entry.visibility))
            append(' ')
            append(name ?: "${entry.componentId}")
            if (childCount > 0) append("  ($childCount)")
            if (preview.isNotBlank()) append(": \"$preview\"")
            if (itemId != null) append(" [item $itemId]")
        }

        styleColor(ImGuiCol.Text, tint(entry.visibility)) {
            selectable("$label###c${entry.interfaceId}_${entry.componentId}", selected) {
                select(entry.interfaceId, entry.componentId)
            }
        }
        if (selected && focusPending) {
            setScrollHereY(0.5f)
            focusPending = false
        }
    }

    /** Drawn / laid out / hidden, so a component that exists but cannot be pointed at still reads as real. */
    private fun badge(visibility: ComponentVisibility) = when (visibility) {
        ComponentVisibility.DRAWN -> "[*]"
        ComponentVisibility.LAID_OUT -> "[~]"
        ComponentVisibility.HIDDEN -> "[ ]"
    }

    private fun tint(visibility: ComponentVisibility) = when (visibility) {
        ComponentVisibility.DRAWN -> ImGuiColors.TEXT_PRIMARY
        ComponentVisibility.LAID_OUT -> ImGuiColors.ACCENT_WARNING
        ComponentVisibility.HIDDEN -> ImGuiColors.TEXT_DISABLED
    }

    private fun ChildScope.renderDetails(entry: InspectedComponent, slotIndex: Int) {
        val comp = entry.component
        val label = runCatching { Gameval.componentLabel(entry.interfaceId, entry.componentId) }
            .getOrDefault("${entry.interfaceId}:${entry.componentId}")

        renderBreadcrumb(entry)
        if (slotIndex >= 0) {
            styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                text("Part $slotIndex inside this component - one of several copies of the same template.")
            }
            smallButton("Back to the container") { selectSlot(NO_SLOT) }
        }

        text(label)
        sameLine()
        button("Copy ID") { copyToClipboard("${entry.interfaceId}:${entry.componentId}") }
        if (copyStatus.isNotEmpty()) {
            sameLine()
            styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) { text(copyStatus) }
        }

        styleColor(ImGuiCol.Text, tint(entry.visibility)) { text(plainState(entry.visibility)) }

        // Only what the thing on screen actually is. Everything positional or structural is one click away
        // below, so a selection reads as a few lines rather than a wall of coordinates.
        val raw = runCatching { comp.text }.getOrDefault("")
        val body = displayText(raw)
        val itemId = heldItem(runCatching { comp.itemId }.getOrDefault(0))

        readout("ifdebug-summary") {
            if (body.isNotBlank()) valueRow("Text", body.take(120))
            // A byte or two of unreadable content is padding or a stale field, not something withheld -
            // reporting it as "not readable" implies there is text here to recover.
            else if (raw.length >= MIN_REPORTABLE_TEXT) valueRow("Text", "(${raw.length} bytes, not readable)")
            if (itemId != null) {
                val stack = runCatching { comp.stackSize }.getOrDefault(0)
                val itemName = runCatching { Gameval.objLabel(itemId) }.getOrDefault("$itemId")
                valueRow("Holds item", if (stack > 1) "$itemName x$stack" else itemName)
            }
            entry.rect?.let { valueRow("On screen at", "${it.x}, ${it.y}   (${it.width} x ${it.height})") }
        }
        if (body.length > 120) {
            button("Copy full text") { copyToClipboard(raw) }
        }

        // Going up is the breadcrumb's job; a second control for it just competes with the first.
        // Only listed for the container itself - a slot child's own vector is the container's, so showing it
        // here would offer to descend into the siblings of the thing already being inspected.
        if (slotIndex < 0) {
            val children = runCatching { comp.slotChildren }.getOrDefault(emptyList())
            if (children.isNotEmpty()) {
                section("Contains")
                renderSlotChildren(children)
            }
        }

        renderScriptHooks(entry)

        section("Technical details")
        readout("ifdebug-tech-rows") {
            valueRow("Interface id", "${entry.interfaceId}")
            valueRow("Component id", "${entry.componentId}")
            valueRow("Parent layer", runCatching { "${comp.parentLayerId}" }.getOrDefault("?"))
            valueRow("Slot id", runCatching { "${comp.slotId}" }.getOrDefault("?"))
            valueRow("Parent-local xy", runCatching { "${comp.parentRelX}, ${comp.parentRelY}" }.getOrDefault("?"))
            valueRow("Layout size", runCatching { "${comp.screenWidth} x ${comp.screenHeight}" }.getOrDefault("?"))
            val graphicId = runCatching { comp.graphicId }.getOrDefault(0)
            if (graphicId != 0) valueRow("Graphic id", "$graphicId")
        }
        properties("ifdebug-jump") {
            row("Jump to interface") { inputInt("##ifid", UIState.interfaceDebugInterfaceId) }
            row("Jump to component") { inputInt("##compid", UIState.interfaceDebugComponentId) }
        }
    }

    /** Wording aimed at someone who has not read the renderer. */
    private fun plainState(visibility: ComponentVisibility) = when (visibility) {
        ComponentVisibility.DRAWN -> "Showing on screen right now"
        ComponentVisibility.LAID_OUT -> "Loaded, but not currently drawn"
        ComponentVisibility.HIDDEN -> "Hidden - nothing is drawn for this"
    }

    /**
     * Ancestor path from the interface down to the selection, each segment clickable.
     *
     * Without it the only cue to where you are in the tree is a pair of raw ids, which says nothing about
     * how deep you are or what contains what.
     */
    private fun ChildScope.renderBreadcrumb(entry: InspectedComponent) {
        val chain = ArrayDeque<Int>()
        var walker = runCatching { entry.component.parent }.getOrNull()
        var guard = 0
        while (walker != null && guard++ < BREADCRUMB_DEPTH) {
            val id = runCatching { walker!!.componentId }.getOrDefault(-1)
            if (id < 0) break
            chain.addFirst(id)
            walker = runCatching { walker!!.parent }.getOrNull()
        }

        val interfaceName = runCatching { Gameval.interfaceLabel(entry.interfaceId) }
            .getOrDefault("${entry.interfaceId}")

        // Names rather than ids wherever the gameval tables have one - a path of bare numbers says nothing
        // about what contains what, which is the only reason to show a path at all.
        styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) { text(interfaceName) }
        chain.forEach { ancestorId ->
            sameLine()
            styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) { text("/") }
            sameLine()
            smallButton("${crumbName(entry.interfaceId, ancestorId)}###crumb$ancestorId") {
                select(entry.interfaceId, ancestorId)
            }
        }
        sameLine()
        styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) { text("/") }
        sameLine()
        styleColor(ImGuiCol.Text, ImGuiColors.ACCENT_PRIMARY) {
            text(crumbName(entry.interfaceId, entry.componentId))
        }
    }

    private fun crumbName(interfaceId: Int, componentId: Int): String =
        runCatching { Gameval.component(interfaceId, componentId)?.substringAfter(':') }.getOrNull()
            ?: "$componentId"

    /**
     * The CS2 scripts this interface is wired to, read from the cache.
     *
     * Static wiring rather than observed calls, so it lists what *would* run without needing the event to
     * happen first. The component's own hooks come first because they are what the selection actually does.
     */
    private fun ChildScope.renderScriptHooks(entry: InspectedComponent) {
        val own = runCatching { InterfaceScripts.forComponent(entry.interfaceId, entry.componentId) }
            .getOrDefault(emptyList())
        val all = runCatching { InterfaceScripts.forInterface(entry.interfaceId) }.getOrDefault(emptyList())
        if (all.isEmpty()) return

        section("Scripts")
        if (own.isEmpty()) {
            styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                text("This part runs no scripts of its own.")
            }
        } else {
            readout("ifdebug-scripts") {
                own.forEach { hook ->
                    val args = if (hook.args.isEmpty()) "" else "  args: ${hook.args.joinToString(", ")}"
                    valueRow(hook.trigger, "script ${hook.scriptId}$args")
                }
            }
        }

        val elsewhere = all.size - own.size
        if (elsewhere > 0) {
            styleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED) {
                textWrapped("$elsewhere more script hook(s) elsewhere in this screen.")
            }
        }
    }

    /**
     * One row per slot child, labelled by what distinguishes it.
     *
     * Every child of a slot vector is an instance of the same template component and reports that
     * template's id, so a grid of identical id buttons says nothing - the slot position and whatever the
     * instance is showing are the only things that tell them apart.
     */
    private fun ChildScope.renderSlotChildren(children: List<InterfaceComponent>) {
        text("${children.size} part(s) inside this one:")
        val selectedSlot = UIState.interfaceDebugSlotIndex.value

        val interfaceId = UIState.interfaceDebugInterfaceId.value
        // Scrolls rather than truncating: an inventory or bank container runs to hundreds of slots, and
        // "and 200 more" hides exactly the ones being looked for.
        child("ifdebug-slots", height = SLOT_LIST_HEIGHT, childFlags = ImGuiChildFlags.Borders) {
            children.forEachIndexed { index, child ->
                val described = runCatching { InterfaceInspector.describe(interfaceId, child) }.getOrNull()
                val preview = displayText(runCatching { child.text }.getOrDefault("")).take(40)
                val itemId = heldItem(runCatching { child.itemId }.getOrDefault(0))

                // Same badge and visibility colour as a tree row, so a part reads the same wherever it appears.
                val label = buildString {
                    described?.let { append(badge(it.visibility)); append(' ') }
                    append("slot $index")
                    if (preview.isNotBlank()) append(": \"$preview\"")
                    if (itemId != null) {
                        append("  ")
                        append(runCatching { Gameval.objLabel(itemId) }.getOrDefault("item $itemId"))
                    }
                }
                val tint = described?.let { tint(it.visibility) } ?: ImGuiColors.TEXT_PRIMARY
                styleColor(ImGuiCol.Text, tint) {
                    selectable("$label###slot$index", index == selectedSlot) { selectSlot(index) }
                }
            }
        }
    }
}
