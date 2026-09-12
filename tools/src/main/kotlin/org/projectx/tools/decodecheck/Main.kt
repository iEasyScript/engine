package org.projectx.tools.decodecheck

import org.projectx.tools.util.DecoderRegistry
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.DecodeReport
import world.gregs.voidps.cache.sqlite.SQLiteCache
import world.gregs.voidps.cache.type.decoder.MapDecoder
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val USAGE = """
Decode check — how much of the cache do our decoders actually understand?

  --cache <dir>   cache to read, opened READ-ONLY   (default ./data/cache)
  --verbose       list every offending definition id
  --help

Trailing bytes prove a record was not fully consumed. Unknown opcodes are exact where a decoder
has an explicit unknown arm. Zero-advance is a heuristic that localises a desync and over-reports,
because zero-payload flag opcodes are legal.
"""

private fun decodeMapSquares(cache: Cache, report: DecodeReport) {
    val decoder = MapDecoder()
    decoder.report = report
    for (mapSquareX in 0 until 128) {
        for (mapSquareY in 0 until 256) {
            decoder.decode(cache, (mapSquareX shl 8) or mapSquareY)
        }
    }
}

fun main(args: Array<String>) {
    if (args.contains("--help") || args.contains("-h")) {
        println(USAGE.trimIndent())
        return
    }
    var cacheDir = "./data/cache"
    var verbose = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--cache" -> cacheDir = args[++i]
            "--verbose" -> verbose = true
            else -> {
                System.err.println("Unknown arg: $a")
                exitProcess(64)
            }
        }
        i++
    }
    val path = Paths.get(cacheDir).toAbsolutePath().normalize()
    if (!path.exists()) {
        System.err.println("No such cache directory: $path")
        exitProcess(1)
    }

    val scan = DecoderRegistry.scan()

    println("==================== Decode check ====================")
    println("  cache (read-only) : $path")
    println("  decoders          : ${scan.decoders.size} discovered from the classpath")
    println("======================================================")

    val cache: Cache = SQLiteCache.load(path, readOnly = true)
    val report = DecodeReport()
    val aborted = LinkedHashMap<String, String>()

    for (decoder in scan.decoders) {
        val name = decoder::class.simpleName ?: "?"
        decoder.report = report
        try {
            decoder.load(cache)
        } catch (e: Throwable) {
            aborted[name] = "${e::class.simpleName}: ${e.message}"
        }
    }
    try {
        decodeMapSquares(cache, report)
    } catch (e: Throwable) {
        aborted["MapDecoder"] = "${e::class.simpleName}: ${e.message}"
    }

    var dirtyTypes = 0
    for (type in report.types.sorted()) {
        val trailing = report.trailing(type).size
        val unknown = report.unknownOpcodeCounts(type)
        val failures = report.failures(type).size
        if (trailing == 0 && unknown.isEmpty() && failures == 0) continue
        dirtyTypes++
        println(
            "  %-26s %7d decoded  %5d trailing  %4d failed  %s".format(
                type, report.decoded(type), trailing, failures,
                if (unknown.isEmpty()) "" else "unknown: " + unknown.entries.sortedByDescending { it.value }
                    .joinToString { "op${it.key} in ${it.value} defs" },
            )
        )
        if (verbose) {
            for (record in report.records.filter { it.type == type }.take(20)) println("      $record")
        }
    }

    println("------------------------------------------------------")
    println("  ${report.types.size} decoder(s), ${report.decoded} definitions decoded")
    if (dirtyTypes == 0) println("  every decoder consumed every record completely")
    else println("  $dirtyTypes decoder(s) with trailing bytes, unknown opcodes or failures")
    if (aborted.isNotEmpty()) {
        println("  ABORTED (decoder threw before finishing):")
        for ((name, cause) in aborted) println("    $name — $cause")
    }
    val unmeasured = scan.unavailable.filter { it.className != MapDecoder::class.simpleName }
    if (unmeasured.isNotEmpty()) {
        println("  NOT MEASURED (the registry cannot drive these itself):")
        for (entry in unmeasured) println("    ${entry.className} — ${entry.reason}")
    }
    cache.close()
}
