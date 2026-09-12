package org.projectx.tools.betascanner

import kotlinx.coroutines.runBlocking
import org.projectx.tools.betascanner.IsolationGuard.ForbiddenOutputException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = """
JS5 beta-cache scanner — connects to a JS5 host (live or beta), lists advertised indices,
diffs them read-only against our live cache, and downloads selected indices to an ISOLATED dir.
Also has a network-free, READ-ONLY --analyze mode that dumps a downloaded index's byte format.

Usage (network: scan/download):
  --host <host>           (required) JS5 host, e.g. content.runescape.com / beta host
  --port <n>              default 43594
  --major <n>             protocol major revision, default 947
  --minor <n>             protocol minor revision, default 0
  --token <s>             jav_config JS5 token for the host (optional; default empty)
  --out <dir>             isolated output dir for downloads, default ./data/betacache
  --live <dir>            override the live cache dir to diff against (default: engine resolution)
  --connections <n>       parallel download connections, default 4
  --scan                  (default) scan + diff only, writes nothing
  --download <sel>        download mode; <sel> = comma index list | missing | all

Usage (offline: analyze/dump — NO network, NO writes into any cache):
  --analyze [index]       dump the byte format of js5-<index>.jcache (default index 67)
  --cache <dir>           cache dir to read (default: --out, else ./data/betacache)
  --report <path>         tee the full dump to a text file (temp/cwd only, never a cache dir)

  -h, --help              show this help

The tool refuses to run if --out (download) or --report (analyze) equals/contains/is inside
the live game cache. --analyze opens a throwaway read-only copy and never writes to the cache.
"""

private fun parseArgs(args: Array<String>): Map<String, String> {
    val flags = mapOf(
        "--host" to true, "--port" to true, "--major" to true, "--minor" to true,
        "--token" to true, "--out" to true, "--live" to true, "--connections" to true,
        "--download" to true, "--scan" to false, "--help" to false, "-h" to false,
        "--cache" to true, "--report" to true,
    )
    val result = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        // --analyze takes an OPTIONAL index value (a bare integer); otherwise the index defaults later.
        if (arg == "--analyze") {
            val next = args.getOrNull(i + 1)
            if (next != null && next.toIntOrNull() != null) {
                result[arg] = next
                i += 2
            } else {
                result[arg] = "true"
                i += 1
            }
            continue
        }
        val takesValue = flags[arg]
            ?: throw IllegalArgumentException("Unknown flag: $arg")
        if (takesValue) {
            val value = args.getOrNull(i + 1)
                ?: throw IllegalArgumentException("Flag $arg requires a value")
            result[arg] = value
            i += 2
        } else {
            result[arg] = "true"
            i += 1
        }
    }
    return result
}

/** Engine-style live cache resolution: RS_CACHE_DIR -> $HOME/Jagex/RuneScape -> launcher dir. */
private fun resolveLiveDir(override: String?): Path {
    if (override != null) return Paths.get(override).toAbsolutePath().normalize()
    System.getenv("RS_CACHE_DIR")?.let { return Paths.get(it).toAbsolutePath().normalize() }
    val home = System.getProperty("user.home")
    val candidates = listOf(
        "$home/Jagex/RuneScape",
        "$home/.local/share/project-x-launcher/Jagex/RuneScape",
    )
    val chosen = candidates.firstOrNull { dir ->
        val p = Paths.get(dir)
        Files.isDirectory(p) && (0..255).any { Files.exists(p.resolve("js5-$it.jcache")) }
    } ?: candidates.first()
    return Paths.get(chosen).toAbsolutePath().normalize()
}

/**
 * Offline, READ-ONLY analyze mode: dump the byte structure of a downloaded `js5-<index>.jcache`.
 * No network and no writes into any cache dir — the only thing written is an optional --report
 * text file, which is isolation-guarded just like a download --out.
 */
private fun runAnalyze(parsed: Map<String, String>) {
    val analyzeVal = parsed["--analyze"] ?: "true"
    val index = if (analyzeVal == "true") 67 else (analyzeVal.toIntOrNull() ?: 67)

    val cacheDir = Paths.get(parsed["--cache"] ?: parsed["--out"] ?: "./data/betacache")
        .toAbsolutePath().normalize()

    val report = parsed["--report"]?.let { Paths.get(it).toAbsolutePath().normalize() }
    if (report != null) {
        try {
            IsolationGuard.check(report)
        } catch (e: ForbiddenOutputException) {
            System.err.println()
            System.err.println("REFUSING TO RUN: --report path is inside a protected cache directory.")
            System.err.println("  ${e.message}")
            System.err.println("The report must never land in a cache directory. Choose a temp/cwd path.")
            exitProcess(3)
        }
        report.parent?.let { Files.createDirectories(it) }
    }

    if (!Files.isDirectory(cacheDir)) {
        System.err.println("Error: cache dir does not exist: $cacheDir")
        System.err.println("Pass --cache <dir> (e.g. \$HOME/Jagex/RuneScape for a self-test, or your --out download dir).")
        exitProcess(1)
    }

    try {
        CacheAnalyzer(cacheDir).analyze(index, report)
    } catch (e: Exception) {
        System.err.println()
        System.err.println("Error: ${e::class.simpleName}: ${e.message}")
        exitProcess(1)
    }
}

fun main(args: Array<String>) {
    val parsed = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Error: ${e.message}")
        println(USAGE)
        exitProcess(64)
    }

    if (parsed.containsKey("--help") || parsed.containsKey("-h") || args.isEmpty()) {
        println(USAGE)
        exitProcess(if (args.isEmpty()) 64 else 0)
    }

    if (parsed.containsKey("--analyze")) {
        runAnalyze(parsed)
        return
    }

    val host = parsed["--host"] ?: run {
        System.err.println("Error: --host is required")
        println(USAGE)
        exitProcess(64)
    }
    val port = parsed["--port"]?.toIntOrNull() ?: 43594
    val major = parsed["--major"]?.toIntOrNull() ?: 947
    val minor = parsed["--minor"]?.toIntOrNull() ?: 0
    val token = parsed["--token"] ?: ""
    val connections = parsed["--connections"]?.toIntOrNull()?.coerceAtLeast(1) ?: 4
    val downloadSelector = parsed["--download"]

    val outDir = Paths.get(parsed["--out"] ?: "./data/betacache").toAbsolutePath().normalize()
    val liveDir = resolveLiveDir(parsed["--live"])

    println("==================== JS5 Beta-Cache Scanner ====================")
    println("  Host        : $host:$port")
    println("  Revision    : $major.$minor")
    println("  Token       : ${if (token.isEmpty()) "(none)" else token}")
    println("  Mode        : ${if (downloadSelector != null) "DOWNLOAD ($downloadSelector)" else "SCAN"}")
    println("  Output dir  : $outDir  (isolated; only written in download mode)")
    println("  Live dir    : $liveDir  (READ-ONLY diff source)")
    println("  Connections : $connections")
    println("================================================================")

    try {
        IsolationGuard.check(outDir)
    } catch (e: ForbiddenOutputException) {
        System.err.println()
        System.err.println("REFUSING TO RUN: ${e.message}")
        System.err.println("The live game cache must never be the output target. Choose a different --out.")
        exitProcess(3)
    }

    val config = BetaScannerConfig(host, port, major, minor, token, outDir, liveDir, connections)
    val scanner = BetaScanner(config)

    try {
        if (downloadSelector != null) {
            runBlocking { scanner.download(downloadSelector) }
        } else {
            scanner.scan()
        }
    } catch (e: HandshakeRejectedException) {
        System.err.println()
        if (e.code == 6) {
            System.err.println("Handshake rejected: revision ${e.major}.${e.minor} (response 6 = GAME_UPDATE) " +
                "was NOT accepted by $host:$port. Try a different --major/--minor.")
        } else {
            System.err.println("Handshake rejected by $host:$port for ${e.major}.${e.minor} (response code ${e.code}).")
        }
        exitProcess(2)
    } catch (e: Exception) {
        System.err.println()
        System.err.println("Error: ${e::class.simpleName}: ${e.message}")
        exitProcess(1)
    }
}
