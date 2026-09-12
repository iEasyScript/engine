package com.projectx.ui.tabs

import com.projectx.ui.UIState
import com.projectx.ui.backend.dsl.scopes.*
import com.projectx.ui.backend.dsl.utils.ImGuiChildFlags
import com.projectx.ui.backend.dsl.utils.ImGuiCol
import com.projectx.ui.backend.dsl.utils.ImGuiColors
import com.projectx.util.EngineLog
import java.io.File
import java.io.RandomAccessFile

object LogsTab {
    private const val TAIL_BYTES = 64L * 1024
    private const val FILTER_WIDTH = 260f

    private val ERROR_MARKERS = listOf("error", "exception", "severe", "fatal", "	at ", "caused by")
    private val WARN_MARKERS = listOf("warn", "deprecated")

    private var lastModifiedTime = 0L

    fun ChildScope.render() {
        text("Filter")
        sameLine()
        setNextItemWidth(FILTER_WIDTH)
        inputText("##log-filter", UIState.logFilterText)
        sameLine()
        checkbox("Errors only", UIState.logErrorsOnly)

        val visible = visibleLines()
        child(id = "LogScrollRegion", height = -40f, childFlags = ImGuiChildFlags.Borders) {
            if (visible.isEmpty()) {
                pushStyleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED)
                textWrapped(if (EngineLog.lines.isEmpty()) "No log output yet." else "No lines match the filter.")
                popStyleColor(1)
            }
            visible.forEach { line ->
                pushStyleColor(ImGuiCol.Text, severityColor(line))
                textWrapped(line)
                popStyleColor(1)
            }
            scrollToBottomIfPinned()
        }

        button("Clear") { EngineLog.lines.clear() }
        sameLine()
        button("Refresh") { updateLogLines() }
        sameLine()
        pushStyleColor(ImGuiCol.Text, ImGuiColors.TEXT_DISABLED)
        text("${visible.size}/${EngineLog.lines.size} lines · ${EngineLog.file?.absolutePath ?: "no log file"}")
        popStyleColor(1)
    }

    private fun visibleLines(): List<String> {
        val query = UIState.logFilterText.value.trim().lowercase()
        return EngineLog.lines.toList().filter { line ->
            (query.isEmpty() || line.lowercase().contains(query)) &&
                (!UIState.logErrorsOnly.value || isError(line))
        }
    }

    private fun isError(line: String): Boolean =
        ERROR_MARKERS.any { line.contains(it, ignoreCase = true) }

    private fun severityColor(line: String): Int = when {
        isError(line) -> ImGuiColors.ACCENT_ERROR
        WARN_MARKERS.any { line.contains(it, ignoreCase = true) } -> ImGuiColors.ACCENT_WARNING
        else -> ImGuiColors.TEXT_PRIMARY
    }

    fun updateLogLines() {
        val logFile = EngineLog.file?.takeIf { it.exists() } ?: return

        val modifiedTime = logFile.lastModified()
        if (modifiedTime > lastModifiedTime) {
            lastModifiedTime = modifiedTime
            val tail = readLastLines(logFile, EngineLog.MAX_LINES)
            EngineLog.lines.clear()
            EngineLog.lines.addAll(tail)
        }
    }

    private fun readLastLines(file: File, count: Int): List<String> =
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val from = (length - TAIL_BYTES).coerceAtLeast(0)
            raf.seek(from)
            val window = ByteArray((length - from).toInt())
            raf.readFully(window)

            val lines = String(window, Charsets.UTF_8).lineSequence().toList()
            // Seeking into the middle of the file can land mid-line, and mid-codepoint for multi-byte
            // UTF-8; both damage only the first line, so drop it whenever the window was truncated.
            val intact = if (from > 0) lines.drop(1) else lines
            intact.filter { it.isNotEmpty() }.takeLast(count)
        }
}
