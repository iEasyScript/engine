package org.projectx.tools.cachesnapshot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.projectx.tools.util.RefTable
import org.projectx.tools.util.parseRefTable
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.compress.DecompressionContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-archive identity for one cache index, taken from its reference table.
 *
 * The ref table already carries the authoritative crc and version Jagex assigns each archive, so
 * change detection needs no blob hashing — that would mean reading the whole cache to learn what
 * the ref table already states. Stored size comes from a length query, not a blob read.
 */
class IndexSnapshot(private val cache: Cache?, private val context: DecompressionContext) {

    data class Header(
        val index: Int,
        val format: Int,
        val revision: Int,
        val flags: Int,
        val named: Boolean,
        val archiveCount: Int,
        val totalFileCount: Int,
        val maxGroupId: Int,
        val bytesRemaining: Int,
    )

    fun refTable(index: Int): RefTable? {
        val raw = cache?.sector(MASTER, index) ?: return null
        return parseRefTable(context, raw, parseFiles = true)
    }

    fun write(index: Int, table: RefTable, dir: Path, storedSizes: Boolean): Header {
        Files.createDirectories(dir)
        val rows = dir.resolve("$index.jsonl")
        Files.newBufferedWriter(rows).use { writer ->
            for (entry in table.entries.sortedBy { it.id }) {
                val files = table.fileIds[entry.id]
                val row = buildJsonObject {
                    put("id", entry.id)
                    put("crc", entry.crc)
                    put("version", entry.version)
                    put("files", entry.fileCount)
                    if (table.named) put("nameHash", entry.nameHash)
                    if (storedSizes && cache != null) put("bytes", cache.sectorSize(index, entry.id))
                    if (files != null && files.size > 1) {
                        put("fileIds", JsonArray(files.map { JsonPrimitive(it) }))
                    }
                }
                writer.write(COMPACT.encodeToString(JsonObject.serializer(), row))
                writer.newLine()
            }
        }
        return Header(
            index = index,
            format = table.format,
            revision = table.revision,
            flags = table.flags,
            named = table.named,
            archiveCount = table.entries.size,
            totalFileCount = table.totalFileCount,
            maxGroupId = table.maxGroupId,
            bytesRemaining = table.bytesRemaining,
        )
    }

    companion object {
        const val MASTER = 255
        val COMPACT = Json { prettyPrint = false; encodeDefaults = true }
    }
}
