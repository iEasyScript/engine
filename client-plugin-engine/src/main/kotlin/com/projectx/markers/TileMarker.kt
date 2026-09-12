package com.projectx.markers

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A single marked tile. [color] null means "inherit the owning group's color". */
data class TileMarker(
    val x: Int,
    val y: Int,
    val plane: Int,
    val color: Int? = null,
    val label: String = "",
) {
    val key: String get() = key(x, y, plane)

    companion object {
        fun key(x: Int, y: Int, plane: Int): String = "$x:$y:$plane"
    }
}

/** A named, separately-toggleable set of markers sharing a default color. */
data class MarkerGroup(
    val name: String,
    val color: Int,
    val enabled: Boolean = true,
    val markers: List<TileMarker> = emptyList(),
)

/**
 * Gson-backed persistence for grouped tile markers, mirroring
 * [com.projectx.quest.data.QuestLibrary]'s on-disk pattern: one JSON file under
 * `~/.projectx/`, holding a list of [MarkerGroup]. Groups are keyed by name and a
 * tile is unique within a group.
 *
 * Persistence is decoupled from edits: a mutation only flips a dirty flag, and a daemon
 * thread coalesces writes once edits go idle (so rapid edits like typing a label don't
 * write per keystroke). Call [flush] to force an immediate write — e.g. when the user
 * leaves edit mode.
 */
object TileMarkerStore {
    private val file = File(System.getProperty("user.home"), ".projectx/tile-markers.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<List<MarkerGroup>>() {}.type

    private val lock = Any()
    private val groups = LinkedHashMap<String, MarkerGroup>()
    @Volatile private var loaded = false

    private val dirty = AtomicBoolean(false)
    @Volatile private var lastChangeNanos = 0L
    private const val IDLE_FLUSH_NANOS = 750_000_000L
    private val flusher by lazy { startFlusher() }
    @Volatile private var flusherThread: Thread? = null

    private const val MAX_UNDO = 50
    private val undoStack = ArrayDeque<List<MarkerGroup>>()
    private val redoStack = ArrayDeque<List<MarkerGroup>>()

    data class Rendered(val x: Int, val y: Int, val plane: Int, val color: Int, val label: String)

    fun groups(): List<MarkerGroup> {
        ensureLoaded()
        synchronized(lock) { return groups.values.toList() }
    }

    /** Markers from enabled groups on [plane], each resolved to its effective color. */
    fun visibleOnPlane(plane: Int): List<Rendered> {
        ensureLoaded()
        synchronized(lock) {
            return groups.values.filter { it.enabled }.flatMap { g ->
                g.markers.filter { it.plane == plane }
                    .map { Rendered(it.x, it.y, plane, it.color ?: g.color, it.label) }
            }
        }
    }

    fun addGroup(name: String, color: Int): Boolean = mutate {
        val n = name.trim()
        if (n.isEmpty() || groups.containsKey(n)) false
        else { groups[n] = MarkerGroup(n, color); true }
    }

    fun removeGroup(name: String): Boolean = mutate { groups.remove(name) != null }

    fun clearGroup(name: String): Boolean = mutate {
        val g = groups[name] ?: return@mutate false
        groups[name] = g.copy(markers = emptyList()); true
    }

    fun clearAll(): Boolean = mutate {
        val any = groups.isNotEmpty(); groups.clear(); any
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean = mutate {
        val g = groups[name] ?: return@mutate false
        groups[name] = g.copy(enabled = enabled); true
    }

    fun setGroupColor(name: String, color: Int): Boolean = mutate {
        val g = groups[name] ?: return@mutate false
        groups[name] = g.copy(color = color); true
    }

    /** Recolors the whole group: sets the group color and clears every per-tile override. */
    fun recolorGroup(name: String, color: Int): Boolean = mutate {
        val g = groups[name] ?: return@mutate false
        groups[name] = g.copy(color = color, markers = g.markers.map { it.copy(color = null) })
        true
    }

    /** Renames a group, preserving insertion order. No-op if [to] is blank or already taken. */
    fun renameGroup(from: String, to: String): Boolean = mutate {
        val target = to.trim()
        if (target.isEmpty() || target == from || target in groups || from !in groups) return@mutate false
        val rebuilt = LinkedHashMap<String, MarkerGroup>(groups.size)
        groups.forEach { (name, g) ->
            if (name == from) rebuilt[target] = g.copy(name = target) else rebuilt[name] = g
        }
        groups.clear(); groups.putAll(rebuilt); true
    }

    // Label edits fire per keystroke, so they skip the undo stack to avoid flooding it with
    // one-character snapshots (a typo is self-correcting; undo is for structural changes).
    fun setLabel(name: String, key: String, label: String): Boolean = mutateSilent {
        val g = groups[name] ?: return@mutateSilent false
        if (g.markers.none { it.key == key }) return@mutateSilent false
        groups[name] = g.copy(markers = g.markers.map { if (it.key == key) it.copy(label = label) else it })
        true
    }

    /**
     * Three-way tile edit within [name]: place when empty, recolor when the tile's
     * effective color differs from [color], remove when it matches. Returns the
     * resulting marker, or null when the tile was toggled off.
     */
    fun markAt(name: String, x: Int, y: Int, plane: Int, color: Int, label: String = ""): TileMarker? {
        ensureLoaded()
        var result: TileMarker? = null
        val changed = synchronized(lock) {
            val g = groups[name] ?: return@synchronized false
            val before = groups.values.toList()
            val k = TileMarker.key(x, y, plane)
            val existing = g.markers.firstOrNull { it.key == k }
            val effective = existing?.color ?: g.color
            val next = when {
                existing == null -> TileMarker(x, y, plane, color, label)
                effective == color -> null
                else -> existing.copy(color = color)
            }
            val rest = g.markers.filter { it.key != k }
            groups[name] = g.copy(markers = if (next == null) rest else rest + next)
            result = next
            pushUndo(before)
            true
        }
        if (changed) markDirty()
        return result
    }

    /** Pure add/remove toggle for a tile in [name]: removes it if present, else adds it. */
    fun toggleTile(name: String, x: Int, y: Int, plane: Int, color: Int): Boolean = mutate {
        val g = groups[name] ?: return@mutate false
        val k = TileMarker.key(x, y, plane)
        val present = g.markers.any { it.key == k }
        groups[name] = if (present) {
            g.copy(markers = g.markers.filter { it.key != k })
        } else {
            g.copy(markers = g.markers + TileMarker(x, y, plane, color))
        }
        true
    }

    fun recolorMarker(name: String, key: String, color: Int): Boolean = mutate {
        val g = groups[name] ?: return@mutate false
        if (g.markers.none { it.key == key }) return@mutate false
        groups[name] = g.copy(markers = g.markers.map { if (it.key == key) it.copy(color = color) else it })
        true
    }

    fun removeMarker(name: String, key: String): Boolean = mutate {
        val g = groups[name] ?: return@mutate false
        val rest = g.markers.filter { it.key != key }
        if (rest.size == g.markers.size) return@mutate false
        groups[name] = g.copy(markers = rest); true
    }

    /** Removes the tile from whichever group(s) hold it. Returns true if anything was removed. */
    fun removeTile(x: Int, y: Int, plane: Int): Boolean = mutate {
        val key = TileMarker.key(x, y, plane)
        var changed = false
        groups.keys.toList().forEach { name ->
            val g = groups.getValue(name)
            val rest = g.markers.filter { it.key != key }
            if (rest.size != g.markers.size) { groups[name] = g.copy(markers = rest); changed = true }
        }
        changed
    }

    /** Adds every tile in the [x1,y1]–[x2,y2] rectangle on [plane] not already present. Returns count added. */
    fun addRect(name: String, x1: Int, y1: Int, x2: Int, y2: Int, plane: Int, color: Int?, label: String = ""): Int {
        ensureLoaded()
        var added = 0
        val changed = synchronized(lock) {
            val g = groups[name] ?: return@synchronized false
            val before = groups.values.toList()
            val have = g.markers.associateBy { it.key }.toMutableMap()
            for (xx in min(x1, x2)..max(x1, x2)) {
                for (yy in min(y1, y2)..max(y1, y2)) {
                    val k = TileMarker.key(xx, yy, plane)
                    if (k !in have) { have[k] = TileMarker(xx, yy, plane, color, label); added++ }
                }
            }
            if (added == 0) return@synchronized false
            groups[name] = g.copy(markers = have.values.toList())
            pushUndo(before)
            true
        }
        if (changed) markDirty()
        return added
    }

    fun rectArea(x1: Int, y1: Int, x2: Int, y2: Int): Long =
        (abs(x1 - x2).toLong() + 1) * (abs(y1 - y2).toLong() + 1)

    private inline fun mutate(block: () -> Boolean): Boolean {
        ensureLoaded()
        val changed = synchronized(lock) {
            val before = groups.values.toList()
            if (block()) { pushUndo(before); true } else false
        }
        if (changed) markDirty()
        return changed
    }

    private inline fun mutateSilent(block: () -> Boolean): Boolean {
        ensureLoaded()
        val changed = synchronized(lock) { block() }
        if (changed) markDirty()
        return changed
    }

    /**
     * Snapshot of the state prior to the most recent change; assumes the caller holds [lock].
     * A fresh edit invalidates any redo history.
     */
    private fun pushUndo(before: List<MarkerGroup>) {
        undoStack.addLast(before)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    fun canUndo(): Boolean = synchronized(lock) { undoStack.isNotEmpty() }
    fun canRedo(): Boolean = synchronized(lock) { redoStack.isNotEmpty() }

    /** Reverts the last change (delete, clear, rename, mark, bulk add, …). */
    fun undo(): Boolean = step(from = undoStack, to = redoStack)

    /** Re-applies the last undone change. */
    fun redo(): Boolean = step(from = redoStack, to = undoStack)

    private fun step(from: ArrayDeque<List<MarkerGroup>>, to: ArrayDeque<List<MarkerGroup>>): Boolean {
        ensureLoaded()
        val moved = synchronized(lock) {
            if (from.isEmpty()) return@synchronized false
            to.addLast(groups.values.toList())
            while (to.size > MAX_UNDO) to.removeFirst()
            val snapshot = from.removeLast()
            groups.clear()
            snapshot.forEach { groups[it.name] = it }
            true
        }
        if (moved) markDirty()
        return moved
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
            runCatching {
                if (!file.isFile) return
                val parsed: List<MarkerGroup> = gson.fromJson(file.readText(), listType) ?: return
                parsed.forEach { groups[it.name] = it }
            }.onFailure { println("[TileMarkerStore] load failed: ${it.message}") }
        }
    }

    private fun markDirty() {
        lastChangeNanos = System.nanoTime()
        dirty.set(true)
        flusher // start the daemon on first edit
    }

    /** Forces an immediate write if there are unsaved edits. Safe to call from any thread. */
    fun flush() = writeToDisk()

    /** Final flush + stop the flusher daemon (only if it was ever started). For engine teardown. */
    fun stopFlusher() {
        flush()
        flusherThread?.interrupt()
    }

    private fun startFlusher(): Thread = Thread({
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(500)
                if (dirty.get() && System.nanoTime() - lastChangeNanos >= IDLE_FLUSH_NANOS) writeToDisk()
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                println("[TileMarkerStore] flusher error: ${t.message}")
            }
        }
    }, "tile-marker-flush").apply { isDaemon = true; flusherThread = this; start() }

    private fun writeToDisk() {
        if (!dirty.compareAndSet(true, false)) return
        runCatching {
            file.parentFile?.mkdirs()
            val snapshot = synchronized(lock) { groups.values.toList() }
            file.writeText(gson.toJson(snapshot, listType))
        }.onFailure {
            dirty.set(true) // retry on the next idle tick
            println("[TileMarkerStore] save failed: ${it.message}")
        }
    }
}
