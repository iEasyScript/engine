package com.projectx.game.cs2

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Ring buffer of intercepted CS2 script executions (id + arguments + return values), populated by the
 * ExecuteScript hook when [enabled]. Drives the Developer "CS2 Trace" panel and the `cs2_trace` MCP
 * tool. Off by default so the per-execution read cost is only paid while actively diagnosing.
 *
 * Blacklisted script ids are never recorded — this filters out the per-tick UI/timer callbacks that
 * otherwise drown the buffer. The blacklist is seeded with the known spammers and persisted to disk,
 * so click-to-blacklist additions survive restarts.
 */
object CS2Trace {
    @Volatile
    var enabled = false

    private const val CAPACITY = 2000

    data class Entry(
        val seq: Long,
        val scriptId: Int,
        val argInts: IntArray,
        val argLongs: LongArray,
        val argStrings: List<String>,
        val retInts: IntArray,
        val retLongs: LongArray,
        val retStrings: List<String>,
    )

    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()
    private var seq = 0L

    /** Per-tick UI/timer callbacks that spam the trace; recorded out by default. */
    private val DEFAULT_BLACKLIST = setOf(8773, 7992, 13824, 10902, 19400, 11491, 12306)
    private val blacklist = ConcurrentHashMap.newKeySet<Int>()
    private val blacklistFile: Path =
        Paths.get(System.getProperty("user.home"), ".projectx", "cs2-trace-blacklist.txt")

    init {
        loadBlacklist()
    }

    fun nextSeq(): Long = synchronized(lock) { ++seq }

    fun record(entry: Entry) {
        if (blacklist.contains(entry.scriptId)) return
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) { buffer.clear() }

    fun isBlacklisted(id: Int): Boolean = blacklist.contains(id)

    fun blacklistedIds(): List<Int> = blacklist.sorted()

    fun addBlacklist(id: Int) {
        if (blacklist.add(id)) {
            synchronized(lock) { buffer.removeAll { it.scriptId == id } }
            saveBlacklist()
        }
    }

    fun removeBlacklist(id: Int) {
        if (blacklist.remove(id)) saveBlacklist()
    }

    private fun loadBlacklist() {
        try {
            if (Files.isRegularFile(blacklistFile)) {
                Files.readAllLines(blacklistFile).mapNotNull { it.trim().toIntOrNull() }.let(blacklist::addAll)
            } else {
                blacklist.addAll(DEFAULT_BLACKLIST)
                saveBlacklist()
            }
        } catch (e: Throwable) {
            blacklist.addAll(DEFAULT_BLACKLIST)
        }
    }

    private fun saveBlacklist() {
        try {
            blacklistFile.parent?.let { Files.createDirectories(it) }
            Files.write(blacklistFile, blacklist.sorted().map { it.toString() })
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
