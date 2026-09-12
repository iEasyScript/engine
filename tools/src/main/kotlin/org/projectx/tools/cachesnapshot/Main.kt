package org.projectx.tools.cachesnapshot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.projectx.tools.betascanner.IsolationGuard
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.sqlite.SQLiteCache
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val USAGE = """
Cache snapshot — unpack a cache into a diffable, gitignored snapshot.

  --cache <dir>    cache to read, opened READ-ONLY   (default ./data/cache)
  --out <dir>      snapshot root                     (default ./data/cache-unpacked)
  --label <name>   snapshot directory name           (default today's date)
  --indices <sel>  comma list and/or a-b ranges, or 'all'   (default all)
  --sizes          include stored container size per archive (adds a length query per archive)

  Remote source (snapshot a JS5 host without downloading any archive data):
  --host <h>       JS5 host, e.g. content.runescape.com
  --port <n>       (default 43594)
  --major <n>      handshake major   (jav_config server_version)
  --minor <n>      handshake minor   (jav_config launcher_sub_version)
  --token <s>      JS5 token         (jav_config param 29; regenerates per fetch)
  --help

Every populated index is captured at archive granularity from its reference table, including
indices with no definition decoder — that is what makes an undecoded format still diffable.
"""

private fun parseSelector(selector: String, available: List<Int>): List<Int> {
    if (selector.equals("all", ignoreCase = true)) return available
    val wanted = LinkedHashSet<Int>()
    for (part in selector.split(",")) {
        val token = part.trim()
        if (token.isEmpty()) continue
        val dash = token.indexOf('-')
        if (dash > 0) {
            val from = token.substring(0, dash).trim().toIntOrNull()
            val to = token.substring(dash + 1).trim().toIntOrNull()
            if (from == null || to == null) throw IllegalArgumentException("Bad range in --indices: '$token'")
            for (i in minOf(from, to)..maxOf(from, to)) wanted.add(i)
        } else {
            wanted.add(token.toIntOrNull() ?: throw IllegalArgumentException("Bad index in --indices: '$token'"))
        }
    }
    return wanted.filter { it in available }
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println(USAGE.trimIndent())
        if (args.isEmpty()) exitProcess(64)
        return
    }

    var cacheDir = "./data/cache"
    var outDir = "./data/cache-unpacked"
    var label = LocalDate.now().toString()
    var selector = "all"
    var sizes = false
    var host: String? = null
    var port = 43594
    var major = 0
    var minor = 0
    var token = ""

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--cache" -> cacheDir = args[++i]
            "--out" -> outDir = args[++i]
            "--label" -> label = args[++i]
            "--indices" -> selector = args[++i]
            "--sizes" -> sizes = true
            "--host" -> host = args[++i]
            "--port" -> port = args[++i].toInt()
            "--major" -> major = args[++i].toInt()
            "--minor" -> minor = args[++i].toInt()
            "--token" -> token = args[++i]
            else -> {
                System.err.println("Unknown arg: $a")
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
    val snapshot = root.resolve(label)

    if (host != null) {
        remoteSnapshot(host, port, major, minor, token, snapshot, label, selector)
        return
    }

    val cachePath = Paths.get(cacheDir).toAbsolutePath().normalize()
    if (!cachePath.exists()) {
        System.err.println("No such cache directory: $cachePath")
        exitProcess(1)
    }

    println("==================== Cache snapshot ====================")
    println("  cache (read-only) : $cachePath")
    println("  snapshot          : $snapshot")
    println("========================================================")

    val cache = SQLiteCache.load(cachePath, readOnly = true)
    DecompressionContext().use { context ->
        val available = cache.indices().toList().sorted()
        val selected = parseSelector(selector, available)
        if (selected.isEmpty()) {
            System.err.println("No indices selected (available: ${available.joinToString(",")})")
            exitProcess(1)
        }
        val writer = IndexSnapshot(cache, context)
        val headers = ArrayList<IndexSnapshot.Header>()
        val unreadable = ArrayList<Int>()
        for (index in selected) {
            val table = writer.refTable(index)
            if (table == null) {
                unreadable.add(index)
                println("  index %-3d  UNREADABLE reference table".format(index))
                continue
            }
            headers.add(writer.write(index, table, snapshot.resolve("indexes"), sizes))
            report(headers.last())
        }
        finish(snapshot, label, cachePath.toString(), headers, unreadable)
    }
    cache.close()
}

private fun report(h: IndexSnapshot.Header) {
    val trailing = if (h.bytesRemaining == 0) "" else "  ⚠ ${h.bytesRemaining} trailing bytes"
    println(
        "  index %-3d  %7d archives  %8d files  rev %-11d %s%s".format(
            h.index, h.archiveCount, h.totalFileCount, h.revision,
            if (h.named) "named" else "", trailing,
        )
    )
}

private fun remoteSnapshot(
    host: String, port: Int, major: Int, minor: Int, token: String,
    snapshot: Path, label: String, selector: String,
) {
    if (major == 0) {
        System.err.println("--major is required with --host (jav_config server_version)")
        exitProcess(64)
    }
    println("==================== Cache snapshot (remote) ====================")
    println("  host      : $host:$port  rev $major.$minor")
    println("  snapshot  : $snapshot")
    println("  reference tables only — no archive data is downloaded")
    println("=================================================================")

    DecompressionContext().use { context ->
        RemoteRefTables(host, port, major, minor, token).use { remote ->
            val master = remote.master(context)
            val available = master.filter { it.populated }.map { it.index }
            val selected = parseSelector(selector, available)
            val headers = ArrayList<IndexSnapshot.Header>()
            val unreadable = ArrayList<Int>()
            val writer = IndexSnapshot(null, context)
            for (index in selected) {
                if (index == RemoteRefTables.MASTER) continue
                val table = try {
                    remote.refTable(index, context)
                } catch (e: Exception) {
                    println("  index %-3d  FAILED: %s".format(index, e.message))
                    unreadable.add(index)
                    continue
                }
                if (table == null) {
                    unreadable.add(index)
                    println("  index %-3d  UNREADABLE reference table".format(index))
                    continue
                }
                headers.add(writer.write(index, table, snapshot.resolve("indexes"), false))
                report(headers.last())
            }
            finish(snapshot, label, "$host:$port rev $major.$minor", headers, unreadable)
        }
    }
}

private fun finish(
    snapshot: Path, label: String, source: String,
    headers: List<IndexSnapshot.Header>, unreadable: List<Int>,
) {
    val meta = buildJsonObject {
        put("label", label)
        put("source", source)
        put("captured", LocalDate.now().toString())
        putJsonObject("indexes") {
            for (h in headers) {
                putJsonObject(h.index.toString()) {
                    put("format", h.format)
                    put("revision", h.revision)
                    put("flags", h.flags)
                    put("named", h.named)
                    put("archives", h.archiveCount)
                    put("files", h.totalFileCount)
                    put("maxGroupId", h.maxGroupId)
                    put("bytesRemaining", h.bytesRemaining)
                }
            }
        }
    }
    val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    Files.createDirectories(snapshot)
    Files.writeString(snapshot.resolve("meta.json"), pretty.encodeToString(JsonObject.serializer(), meta))

    val incomplete = headers.filter { it.bytesRemaining != 0 }
    println("--------------------------------------------------------")
    println("  ${headers.size} index(es), ${headers.sumOf { it.archiveCount }} archives -> $snapshot")
    if (unreadable.isNotEmpty()) println("  unreadable reference tables: ${unreadable.joinToString(",")}")
    if (incomplete.isEmpty()) {
        println("  reference tables fully parsed (0 trailing bytes on every index)")
    } else {
        println("  ⚠ INCOMPLETE parse on ${incomplete.size} index(es): ${incomplete.joinToString(",") { "${it.index}(${it.bytesRemaining}B)" }}")
    }
}
