package org.projectx.tools.cachediff

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension

/**
 * Field-level comparison of two unpacked snapshots.
 *
 * Both sides are written in ascending id order, so a merge join reads each file once and never holds
 * more than one record per side — the whole cache decoded is far too large to load twice.
 */
class DecodedDiff(private val before: Path, private val after: Path) {

    class FieldChange(val field: String, val before: String, val after: String)

    class IdChange(val id: Int, val fields: List<FieldChange>, val fieldsOmitted: Int)

    class TypeDelta(
        val type: String,
        val beforeRecords: Int,
        val afterRecords: Int,
        val added: List<Int>,
        val removed: List<Int>,
        val addedTotal: Int,
        val removedTotal: Int,
        val changedTotal: Int,
        val changes: List<IdChange>,
        val presentBefore: Boolean,
        val presentAfter: Boolean,
    ) {
        val quiet: Boolean get() = addedTotal == 0 && removedTotal == 0 && changedTotal == 0
        val changesOmitted: Int get() = changedTotal - changes.size
    }

    class Limits(val changedIds: Int, val fields: Int, val valueChars: Int, val sampleIds: Int)

    fun types(): List<String> = (typesIn(before) + typesIn(after)).distinct().sorted()

    fun diff(type: String, limits: Limits): TypeDelta {
        val beforeFile = before.resolve(TYPES).resolve("$type.jsonl")
        val afterFile = after.resolve(TYPES).resolve("$type.jsonl")
        val added = ArrayList<Int>()
        val removed = ArrayList<Int>()
        val changes = ArrayList<IdChange>()
        var addedTotal = 0
        var removedTotal = 0
        var changedTotal = 0
        var beforeRecords = 0
        var afterRecords = 0

        reader(beforeFile).use { left ->
            reader(afterFile).use { right ->
                var oldRow = left.next()
                var newRow = right.next()
                while (oldRow != null || newRow != null) {
                    val takeOld = newRow == null || (oldRow != null && oldRow.id < newRow.id)
                    val takeNew = oldRow == null || (newRow != null && newRow.id < oldRow.id)
                    when {
                        takeOld -> {
                            beforeRecords++
                            removedTotal++
                            if (removed.size < limits.sampleIds) removed.add(oldRow!!.id)
                            oldRow = left.next()
                        }
                        takeNew -> {
                            afterRecords++
                            addedTotal++
                            if (added.size < limits.sampleIds) added.add(newRow!!.id)
                            newRow = right.next()
                        }
                        else -> {
                            beforeRecords++
                            afterRecords++
                            val change = compare(oldRow!!.row, newRow!!.row, limits)
                            if (change != null) {
                                changedTotal++
                                if (changes.size < limits.changedIds) changes.add(change.at(oldRow.id))
                            }
                            oldRow = left.next()
                            newRow = right.next()
                        }
                    }
                }
            }
        }
        return TypeDelta(
            type, beforeRecords, afterRecords, added, removed, addedTotal, removedTotal, changedTotal,
            changes, beforeFile.exists(), afterFile.exists(),
        )
    }

    private class PendingChange(val fields: List<FieldChange>, val omitted: Int) {
        fun at(id: Int) = IdChange(id, fields, omitted)
    }

    private fun compare(old: JsonObject, new: JsonObject, limits: Limits): PendingChange? {
        if (old == new) return null
        val fields = ArrayList<FieldChange>()
        var omitted = 0
        for (field in (old.keys + new.keys).sorted()) {
            if (field == "id") continue
            val left = old[field] ?: JsonNull
            val right = new[field] ?: JsonNull
            if (left == right) continue
            if (fields.size >= limits.fields) {
                omitted++
                continue
            }
            fields.add(FieldChange(field, render(left, limits.valueChars), render(right, limits.valueChars)))
        }
        if (fields.isEmpty() && omitted == 0) return null
        return PendingChange(fields, omitted)
    }

    private fun render(value: JsonElement, valueChars: Int): String {
        val text = when {
            value is JsonNull -> "null"
            value is JsonPrimitive && value.isString -> value.content
            else -> COMPACT.encodeToString(JsonElement.serializer(), value)
        }
        if (text.length <= valueChars) return text
        return text.take(valueChars) + "…(+${text.length - valueChars} chars)"
    }

    private class Row(val id: Int, val row: JsonObject)

    private class Rows(private val reader: BufferedReader?) : AutoCloseable {
        fun next(): Row? {
            val source = reader ?: return null
            while (true) {
                val line = source.readLine() ?: return null
                if (line.isBlank()) continue
                val row = COMPACT.parseToJsonElement(line).jsonObject
                return Row(row.getValue("id").jsonPrimitive.int, row)
            }
        }

        override fun close() {
            reader?.close()
        }
    }

    private fun reader(file: Path) = Rows(if (file.exists()) Files.newBufferedReader(file) else null)

    private fun typesIn(root: Path): List<String> {
        val dir = root.resolve(TYPES)
        if (!dir.exists()) return emptyList()
        Files.list(dir).use { stream ->
            return stream.filter { it.fileName.toString().endsWith(".jsonl") }
                .map { it.nameWithoutExtension }
                .toList()
        }
    }

    companion object {
        const val TYPES = "types"
        private val COMPACT = Json { prettyPrint = false; isLenient = true }
    }
}
