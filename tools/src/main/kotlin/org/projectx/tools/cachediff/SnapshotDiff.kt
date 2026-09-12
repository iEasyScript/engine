package org.projectx.tools.cachediff

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

data class ArchiveIdentity(val crc: Int, val version: Int, val files: Int)

data class IndexDelta(
    val index: Int,
    val added: List<Int>,
    val removed: List<Int>,
    val changed: List<Int>,
    val versionOnly: List<Int>,
    val fileCountChanged: Map<Int, Pair<Int, Int>>,
    val beforeArchives: Int,
    val afterArchives: Int,
) {
    val touched: Int get() = added.size + removed.size + changed.size
    val quiet: Boolean get() = touched == 0 && versionOnly.isEmpty()
}

class SnapshotDiff(private val before: Path, private val after: Path) {

    fun indexes(): List<Int> = (indexesIn(before) + indexesIn(after)).distinct().sorted()

    fun diff(index: Int): IndexDelta {
        val a = read(before, index)
        val b = read(after, index)
        val added = ArrayList<Int>()
        val removed = ArrayList<Int>()
        val changed = ArrayList<Int>()
        val versionOnly = ArrayList<Int>()
        val fileCountChanged = LinkedHashMap<Int, Pair<Int, Int>>()

        for (id in b.keys.sorted()) {
            val old = a.get(id)
            val new = b.get(id)!!
            if (old == null) {
                added.add(id)
                continue
            }
            if (old.crc != new.crc) {
                changed.add(id)
                if (old.files != new.files) fileCountChanged[id] = old.files to new.files
            } else if (old.version != new.version) {
                versionOnly.add(id)
            }
        }
        for (id in a.keys.sorted()) if (!b.containsKey(id)) removed.add(id)

        return IndexDelta(index, added, removed, changed, versionOnly, fileCountChanged, a.size, b.size)
    }

    private fun indexesIn(root: Path): List<Int> {
        val dir = root.resolve("indexes")
        if (!dir.exists()) return emptyList()
        Files.list(dir).use { stream ->
            return stream.map { it.fileName.toString() }
                .filter { it.endsWith(".jsonl") }
                .map { it.removeSuffix(".jsonl").toIntOrNull() ?: -1 }
                .filter { it >= 0 }
                .sorted()
                .toList()
        }
    }

    private fun read(root: Path, index: Int): Int2ObjectOpenHashMap<ArchiveIdentity> {
        val map = Int2ObjectOpenHashMap<ArchiveIdentity>()
        val file = root.resolve("indexes").resolve("$index.jsonl")
        if (!file.exists()) return map
        Files.newBufferedReader(file).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val row = LENIENT.parseToJsonElement(line).jsonObject
                map.put(row.int("id"), ArchiveIdentity(row.int("crc"), row.int("version"), row.int("files")))
            }
        }
        return map
    }

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    companion object {
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
