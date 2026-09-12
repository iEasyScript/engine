package org.projectx.tools.cacheunpack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.projectx.tools.betascanner.IsolationGuard
import world.gregs.voidps.cache.cs2.Cs2OpcodeTable
import org.projectx.tools.util.DecoderRegistry
import org.projectx.tools.util.IndexLabels
import org.projectx.tools.util.parseRefTable
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val MANIFEST = "unpack-manifest.json"

private const val USAGE = """
Cache unpack — decode every definition in the cache into diffable, field-named JSONL.

  --cache <dir>     cache to read, opened READ-ONLY        (default ./data/cache)
  --out <dir>       snapshot root                          (default ./data/cache-unpacked)
  --label <name>    snapshot directory name                (default today's date)
  --types <sel>     comma list of type names, or 'all'     (default all)
  --indices <sel>   comma list and/or a-b ranges, or 'all' (default all)
  --array-limit <n> inline arrays up to n elements, digest longer ones   (default 64)
  --keep <n>        retain the n most recent unpacked snapshots          (default 3)
  --no-prune        retain every snapshot
  --help

One file per type under types/, one record per line, ascending id — a re-run over an unchanged
cache is byte-identical. Types and indices with no decoder are listed in the manifest so an absent
file never means "no data" when it really means "not decodable yet".
"""

private fun parseIndices(selector: String): Set<Int>? {
    if (selector.equals("all", ignoreCase = true)) return null
    val wanted = LinkedHashSet<Int>()
    for (part in selector.split(",")) {
        val token = part.trim()
        if (token.isEmpty()) continue
        val dash = token.indexOf('-')
        if (dash > 0) {
            val from = token.substring(0, dash).trim().toInt()
            val to = token.substring(dash + 1).trim().toInt()
            for (index in minOf(from, to)..maxOf(from, to)) wanted.add(index)
        } else {
            wanted.add(token.toInt())
        }
    }
    return wanted
}

private fun parseTypes(selector: String): Set<String>? {
    if (selector.equals("all", ignoreCase = true)) return null
    return selector.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}

fun main(args: Array<String>) {
    if (args.contains("--help") || args.contains("-h")) {
        println(USAGE.trimIndent())
        return
    }

    var cacheDir = "./data/cache"
    var outDir = "./data/cache-unpacked"
    var label = LocalDate.now().toString()
    var typeSelector = "all"
    var indexSelector = "all"
    var arrayLimit = 64
    var keep = 3
    var prune = true

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--cache" -> cacheDir = args[++i]
            "--out" -> outDir = args[++i]
            "--label" -> label = args[++i]
            "--types" -> typeSelector = args[++i]
            "--indices" -> indexSelector = args[++i]
            "--array-limit" -> arrayLimit = args[++i].toInt()
            "--keep" -> keep = args[++i].toInt()
            "--no-prune" -> prune = false
            else -> {
                System.err.println("Unknown arg: $arg")
                println(USAGE.trimIndent())
                exitProcess(64)
            }
        }
        i++
    }

    val root = Paths.get(outDir).toAbsolutePath().normalize()
    try {
        IsolationGuard.check(root)
    } catch (e: IsolationGuard.ForbiddenOutputException) {
        System.err.println(e.message)
        exitProcess(3)
    }
    val cachePath = Paths.get(cacheDir).toAbsolutePath().normalize()
    if (!cachePath.exists()) {
        System.err.println("No such cache directory: $cachePath")
        exitProcess(1)
    }

    val snapshot = root.resolve(label)
    val wantedIndices = parseIndices(indexSelector)
    val wantedTypes = parseTypes(typeSelector)
    val scan = DecoderRegistry.scan()

    println("==================== Cache unpack ====================")
    println("  cache (read-only) : $cachePath")
    println("  snapshot          : $snapshot")
    println("  decoders          : ${scan.decoders.size}")
    println("======================================================")

    val started = System.currentTimeMillis()
    val cache = SQLiteCache.load(cachePath, readOnly = true)
    val json = DefinitionJson(arrayLimit)
    val clientScripts = ClientScriptRecord.projection(cache)
    if (clientScripts == null) {
        println("  no solved opcode table for index-12 crc ${Cs2OpcodeTable.indexCrc(cache)}: clientscript instructions stay as bytes")
    }
    val unpacker = TypeUnpacker(json, clientScripts ?: { it })
    val results = LinkedHashMap<String, TypeReport>()
    val deselected = ArrayList<Pair<String, String>>()

    for (decoder in scan.decoders) {
        val type = DecoderRegistry.typeName(decoder)
        if (wantedTypes != null && type !in wantedTypes) {
            deselected.add(type to "not in --types")
            continue
        }
        if (wantedIndices != null && decoder.index !in wantedIndices) {
            deselected.add(type to "index ${decoder.index} not in --indices")
            continue
        }
        val file = snapshot.resolve("types").resolve("$type.jsonl")
        val result = try {
            unpacker.unpack(decoder, cache, file)
        } catch (e: Throwable) {
            println("  %-24s ABORTED: %s".format(type, "${e::class.simpleName}: ${e.message}"))
            results[type] = TypeReport(type, decoder, 0, 0, 0, 0, null, 0, "${e::class.simpleName}: ${e.message}")
            continue
        }
        results[type] = TypeReport(
            type, decoder, result.records, result.scanned, result.defaulted,
            result.failures, result.firstFailure, result.bytes, null,
        )
        println(
            "  %-24s idx %-3d %8d records  %8d scanned%s".format(
                type, decoder.index, result.records, result.scanned,
                if (result.failures == 0) "" else "  ⚠ ${result.failures} failed",
            )
        )
    }

    val revisions = indexRevisions(cache)
    val decodedIndices = scan.decoders.map { it.index }.toSet()
    val undecodedIndices = cache.indices().toList().sorted().filter { it !in decodedIndices }
    writeManifest(snapshot, label, cachePath, arrayLimit, results.values, scan, deselected, revisions, undecodedIndices)
    cache.close()

    val records = results.values.sumOf { it.records }
    val bytes = results.values.sumOf { it.bytes }
    val failures = results.values.sumOf { it.failures }
    println("------------------------------------------------------")
    println("  ${results.size} type(s), $records records, ${bytes / 1024 / 1024} MiB")
    println("  ${undecodedIndices.size} populated index(es) have no decoder — listed in $MANIFEST")
    if (failures > 0) println("  ⚠ $failures definition(s) failed to decode")
    println("  ${System.currentTimeMillis() - started} ms")
    if (prune) prune(root, snapshot, keep)
}

private class TypeReport(
    val type: String,
    val decoder: TypeDecoder<out CacheType>,
    val records: Int,
    val scanned: Int,
    val defaulted: Int,
    val failures: Int,
    val firstFailure: String?,
    val bytes: Long,
    val aborted: String?,
)

private fun indexRevisions(cache: Cache): Map<Int, Int> {
    val revisions = LinkedHashMap<Int, Int>()
    DecompressionContext().use { context ->
        for (index in cache.indices().toList().sorted()) {
            val raw = cache.sector(255, index) ?: continue
            val table = parseRefTable(context, raw) ?: continue
            revisions[index] = table.revision
        }
    }
    return revisions
}

private fun writeManifest(
    snapshot: Path,
    label: String,
    cachePath: Path,
    arrayLimit: Int,
    reports: Collection<TypeReport>,
    scan: DecoderRegistry.Scan,
    deselected: List<Pair<String, String>>,
    revisions: Map<Int, Int>,
    undecodedIndices: List<Int>,
) {
    val manifest = buildJsonObject {
        put("label", label)
        put("source", cachePath.toString())
        put("arrayLimit", arrayLimit)
        put(
            "idsOmitted",
            "An id inside idsScanned but absent from its file has no data in the cache. Records that " +
                "decode to nothing but their defaults are kept and counted as defaultOnlyRecords.",
        )
        put(
            "arrays",
            "Primitive arrays longer than arrayLimit are stored as a length plus digest instead of " +
                "their elements, so bulk pixel and glyph payloads still diff without dwarfing the rest.",
        )
        putJsonObject("cacheRevisions") {
            for ((index, revision) in revisions) put(index.toString(), revision)
        }
        putJsonArray("types") {
            for (report in reports.sortedBy { it.type }) {
                add(
                    buildJsonObject {
                        put("type", report.type)
                        put("decoder", report.decoder::class.simpleName ?: "?")
                        put("index", report.decoder.index)
                        put("file", "types/${report.type}.jsonl")
                        put("records", report.records)
                        put("idsScanned", report.scanned)
                        put("defaultOnlyRecords", report.defaulted)
                        put("failures", report.failures)
                        put("bytes", report.bytes)
                        if (report.firstFailure != null) put("firstFailure", report.firstFailure)
                        if (report.aborted != null) put("aborted", report.aborted)
                    }
                )
            }
        }
        putJsonObject("totals") {
            put("types", reports.size)
            put("records", reports.sumOf { it.records })
            put("failures", reports.sumOf { it.failures })
            put("bytes", reports.sumOf { it.bytes })
        }
        putJsonObject("unpacked") {
            put(
                "note",
                "Everything listed here is present in the cache but absent from types/ — an absent " +
                    "file is never ambiguous between 'no data' and 'not decodable'.",
            )
            putJsonArray("decoders") {
                for (entry in scan.unavailable) {
                    add(
                        buildJsonObject {
                            put("decoder", entry.className)
                            put("reason", entry.reason)
                        }
                    )
                }
            }
            putJsonArray("types") {
                for ((type, reason) in deselected.sortedBy { it.first }) {
                    add(
                        buildJsonObject {
                            put("type", type)
                            put("reason", reason)
                        }
                    )
                }
            }
            putJsonArray("indices") {
                for (index in undecodedIndices) {
                    add(
                        buildJsonObject {
                            put("index", index)
                            put("label", IndexLabels.label(index))
                            put("reason", "no definition decoder — archive identity only, see cacheSnapshot indexes/$index.jsonl")
                        }
                    )
                }
            }
        }
    }
    val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    Files.createDirectories(snapshot)
    Files.writeString(snapshot.resolve(MANIFEST), pretty.encodeToString(JsonObject.serializer(), manifest))
}

/**
 * Only directories this tool produced are candidates for removal, so ref-table-only snapshots taken
 * by cacheSnapshot in the same root are never pruned out from under their owner.
 */
private fun prune(root: Path, keeping: Path, keep: Int) {
    if (keep <= 0) return
    val snapshots = Files.list(root).use { stream ->
        stream.filter { Files.isDirectory(it) && it.resolve(MANIFEST).exists() }
            .toList()
            .sortedByDescending { Files.getLastModifiedTime(it.resolve(MANIFEST)).toMillis() }
    }
    val stale = snapshots.drop(keep).filter { it != keeping }
    if (stale.isEmpty()) return
    for (directory in stale) {
        Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        println("  pruned ${directory.fileName}")
    }
    println("  retaining the $keep most recent unpacked snapshot(s)")
}
