package com.projectx.store

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * One script's part of a session log: from each start marker to its stop marker, with every line in between, since
 * clicks and engine lines carry no script name. Logs from before the markers existed fall back to the script's own
 * `[name]` lines and what lies between them.
 */
internal object ScriptLogExcerpt {
    private val STAMP = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.\d{3}] ?""")
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val LEGACY_GAP: Duration = Duration.ofMinutes(20)

    fun startMarker(name: String, version: String, simpleName: String) = "[Scripts] Started $name v$version ($simpleName)"

    fun stopMarker(name: String, simpleName: String) = "[Scripts] Stopped $name ($simpleName)"

    fun excerpt(lines: List<String>, name: String, simpleName: String, since: LocalDateTime): List<String> {
        val entries = entries(lines)
        val tag = "($simpleName)"
        val isStart = { e: Entry -> e.body.startsWith("[Scripts] Started ") && e.body.endsWith(tag) }
        val isStop = { e: Entry -> e.body.startsWith("[Scripts] Stopped ") && e.body.endsWith(tag) }
        val isOwn = { e: Entry -> e.body.startsWith("[$name]") || e.body == "$simpleName started." || e.body == "$simpleName stopped." }

        val keep = BooleanArray(entries.size)
        if (entries.any(isStart)) {
            var running = false
            entries.forEachIndexed { i, e ->
                if (isStart(e)) running = true
                if (running || isOwn(e)) keep[i] = true
                if (isStop(e)) running = false
            }
        } else {
            var last = -1
            entries.forEachIndexed { i, e ->
                if (!isOwn(e)) return@forEachIndexed
                if (last >= 0 && near(entries[last].at, e.at)) (last..i).forEach { keep[it] = true } else keep[i] = true
                last = i
            }
        }
        return entries.filterIndexed { i, e -> keep[i] && e.at != null && !e.at.isBefore(since) }.flatMap { it.lines }
    }

    private fun near(a: LocalDateTime?, b: LocalDateTime?) = a != null && b != null && Duration.between(a, b) <= LEGACY_GAP

    // A stack trace or a tab-indented detail line belongs to the stamped line above it.
    private fun entries(lines: List<String>): List<Entry> {
        val entries = ArrayList<Entry>()
        for (line in lines) {
            val stamp = STAMP.find(line)
            if (stamp != null || entries.isEmpty()) {
                val at = stamp?.let { runCatching { LocalDateTime.parse(it.groupValues[1], FORMAT) }.getOrNull() }
                entries += Entry(at, stamp?.let { line.substring(it.range.last + 1) } ?: line, mutableListOf(line))
            } else {
                entries.last().lines += line
            }
        }
        return entries
    }

    private class Entry(val at: LocalDateTime?, val body: String, val lines: MutableList<String>)
}
