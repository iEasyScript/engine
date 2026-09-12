package com.projectx.ui.tabs

import com.projectx.game.cs2.CS2Trace
import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiTableFlags

object CS2TraceTab {
    fun ChildScope.render() {
        section("Interception")
        properties("cs2-capture") {
            row("Intercept CS2 calls") {
                checkbox("##cs2Enabled", UIState.cs2TraceEnabled.value) { on ->
                    UIState.cs2TraceEnabled.value = on
                    CS2Trace.enabled = on
                }
            }
            row("Show args") { checkbox("##cs2ShowArgs", UIState.cs2TraceShowArgs) }
        }

        section("Filter")
        properties("cs2-filter") {
            row("Script id (-1 = all)") { inputInt("##cs2FilterId", UIState.cs2TraceFilterId) }
            row("Search (id / value)") { inputText("##cs2Search", UIState.cs2TraceSearch) }
        }

        section("Blacklist")
        textWrapped("Click a Script id in the table to hide it; click it here to bring it back.")
        val blacklisted = CS2Trace.blacklistedIds()
        if (blacklisted.isEmpty()) {
            text("Nothing hidden.")
        } else {
            blacklisted.forEachIndexed { index, id ->
                if (index > 0) sameLine()
                button("$id##unblk") { CS2Trace.removeBlacklist(id) }
            }
        }

        val all = CS2Trace.snapshot()
        val filterId = UIState.cs2TraceFilterId.value
        val search = UIState.cs2TraceSearch.value
        val showArgs = UIState.cs2TraceShowArgs.value
        val entries = all.asReversed()
            .filter { filterId < 0 || it.scriptId == filterId }
            .filter { search.isEmpty() || matches(it, search) }
            .take(500)

        section("Calls")
        text("Showing ${entries.size} of ${all.size} captured" + if (!CS2Trace.enabled) "  (interception OFF)" else "")
        sameLine()
        button("Clear") { CS2Trace.clear() }

        table(
            id = "CS2TraceTable",
            columns = if (showArgs) 4 else 3,
            flags = ImGuiTableFlags.SizingStretchSame or ImGuiTableFlags.BordersInnerH
        ) {
            setupColumn("#")
            setupColumn("Script")
            if (showArgs) setupColumn("Args")
            setupColumn("Returns")
            headersRow()

            entries.forEach { e ->
                nextRow()
                nextColumn(); text(e.seq.toString())
                nextColumn(); button("${e.scriptId}##blk${e.seq}") { CS2Trace.addBlacklist(e.scriptId) }
                if (showArgs) { nextColumn(); text(stackText(e.argInts, e.argLongs, e.argStrings)) }
                nextColumn(); text(stackText(e.retInts, e.retLongs, e.retStrings))
            }
        }
    }

    private fun matches(e: CS2Trace.Entry, q: String): Boolean =
        e.scriptId.toString().contains(q) ||
            stackText(e.argInts, e.argLongs, e.argStrings).contains(q, ignoreCase = true) ||
            stackText(e.retInts, e.retLongs, e.retStrings).contains(q, ignoreCase = true)

    private fun stackText(ints: IntArray, longs: LongArray, strings: List<String>): String {
        val sb = StringBuilder()
        if (ints.isNotEmpty()) sb.append("i:").append(ints.joinToString(",")).append(' ')
        if (longs.isNotEmpty()) sb.append("l:").append(longs.joinToString(",")).append(' ')
        if (strings.isNotEmpty()) sb.append("s:[").append(strings.joinToString("|")).append(']')
        return sb.toString().trim().ifEmpty { "-" }
    }
}
