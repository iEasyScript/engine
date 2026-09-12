package org.projectx.tools.clientupdater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant

/**
 * Downloads the latest NXT game client (`rs2client`) for every major OS from Jagex's CDN and checks
 * each against the copy in ./data/client/<os>/.
 *
 * For each OS it fetches `jav_config.ws?binaryType=N` to learn the live `download_crc_0` (CRC32 of
 * the decompressed binary) and `server_version`, then compares that CRC against the local file.
 * MISSING binaries are filled in by default; OUTDATED ones are reported and only replaced with
 * `--update`. The download is LZMA-alone compressed and CRC-verified after decompression. The
 * per-OS Jagex launcher (`rs3*`) is reported but acquired by the cross-platform Rust launcher.
 *
 * Run:  ./gradlew :tools:run -PmainClass=org.projectx.tools.clientupdater.MainKt --args="..."
 *
 * Flags:
 *   --dir <path>   Client root to check/update      (default ./data/client)
 *   --os a,b,c     Subset of {linux,windows,macos}  (default: all three)
 *   --check        Dry run: report status only, never download or write
 *   --update       Also replace OUTDATED existing binaries (not just fill MISSING ones)
 *   --force        Re-download every selected target even if UP-TO-DATE
 *   -h, --help     Show this help
 */
fun main(rawArgs: Array<String>) {
    val args = ArgParser(rawArgs)
    if (args.flag("-h", "--help")) {
        printHelp()
        return
    }

    val rootDir = File(args.value("--dir") ?: "./data/client")
    val checkOnly = args.flag("--check")
    val update = args.flag("--update")
    val force = args.flag("--force")

    val targets: List<OsTarget> = when (val os = args.value("--os")) {
        null -> OsTarget.defaultSet
        else -> os.split(",").mapNotNull { spec ->
            OsTarget.byKey(spec.trim()).also {
                if (it == null) System.err.println("Unknown OS '$spec' — known: ${OsTarget.entries.joinToString(",") { t -> t.key }}")
            }
        }
    }
    if (targets.isEmpty()) {
        System.err.println("No valid OS targets selected.")
        return
    }

    println("Client root : ${rootDir.absolutePath}")
    println("Targets     : ${targets.joinToString(", ") { it.key }}")
    println("Mode        : " + buildString {
        append(if (checkOnly) "check-only" else "download")
        if (!checkOnly) {
            append(if (update) " +replace-outdated" else " (fill-missing only; --update to replace outdated)")
            if (force) append(" +force")
        }
    })
    println()

    val updater = ClientUpdater(UpdateOptions(rootDir, checkOnly, update, force))
    val results = targets.map { target ->
        print("• ${target.label} (binaryType=${target.binaryType})... ")
        System.out.flush()
        val r = updater.process(target)
        println(oneLineStatus(r))
        r
    }

    println()
    printTable(results)

    if (!checkOnly) writeManifest(rootDir, results)

    println()
    val missingLaunchers = results.filterNot { it.launcherPresent }
    if (missingLaunchers.isNotEmpty()) {
        println("ℹ launcher (rs3*) not yet present for: " +
            missingLaunchers.joinToString(", ") { "${it.target.key} (${it.target.launcherPath})" } +
            ". The cross-platform launcher seeds these into the OS folders.")
    }
    val outdated = results.filter { it.action == Action.SKIPPED_OUTDATED }
    if (outdated.isNotEmpty()) {
        println("ℹ ${outdated.size} target(s) are OUTDATED but were left untouched: " +
            outdated.joinToString(", ") { it.target.key } + ". Re-run with --update to replace them.")
    }
    val failures = results.count { it.status == Status.ERROR || it.action == Action.FAILED }
    if (failures > 0) {
        System.err.println("⚠ $failures target(s) failed — see notes above.")
        kotlin.system.exitProcess(1)
    }
}

private fun oneLineStatus(r: TargetResult): String {
    val rev = r.serverVersion?.let { "rev $it" } ?: "rev ?"
    val verb = when (r.action) {
        Action.INSTALLED -> "downloaded → ${r.installedAt?.path}"
        Action.REPLACED -> "replaced → ${r.installedAt?.path}"
        Action.SKIPPED_OUTDATED -> "OUTDATED (kept; use --update)"
        Action.FAILED -> "FAILED: ${r.note}"
        Action.NONE -> when (r.status) {
            Status.UP_TO_DATE -> "up to date"
            Status.MISSING -> "MISSING (not downloaded)"
            Status.OUTDATED -> "OUTDATED"
            Status.ERROR -> "ERROR: ${r.note}"
        }
    }
    return "$rev — ${r.status} — $verb"
}

private fun printTable(results: List<TargetResult>) {
    val header = listOf("OS", "Rev", "Latest CRC", "Local CRC", "Client", "Launcher")
    val rows = results.map { r ->
        listOf(
            r.target.key,
            r.serverVersion ?: "?",
            r.latestCrc?.toString() ?: "-",
            r.localCrc?.toString() ?: "(none)",
            clientCell(r),
            if (r.launcherPresent) "present" else "MISSING",
        )
    }
    val widths = header.indices.map { c -> (rows + listOf(header)).maxOf { it[c].length } }
    fun line(cells: List<String>) = cells.mapIndexed { i, s -> s.padEnd(widths[i]) }.joinToString("  ")
    println(line(header))
    println(widths.joinToString("  ") { "-".repeat(it) })
    rows.forEach { println(line(it)) }
}

private fun clientCell(r: TargetResult): String = when (r.action) {
    Action.INSTALLED -> "installed"
    Action.REPLACED -> "replaced"
    Action.SKIPPED_OUTDATED -> "OUTDATED (--update)"
    Action.FAILED -> "FAILED"
    Action.NONE -> r.status.name
}

/** Records what we last installed per OS so future runs (and humans) have a version trail. */
private fun writeManifest(rootDir: File, results: List<TargetResult>) {
    val manifest = File(rootDir, "clients.manifest.json")
    val existing = runCatching {
        if (manifest.isFile) Json.parseToJsonElement(manifest.readText()) as? JsonObject else null
    }.getOrNull()
    val priorClients = (existing?.get("clients") as? JsonObject)

    val obj = buildJsonObject {
        put("updatedAt", Instant.now().toString())
        putJsonObject("clients") {
            // Carry forward entries for OS targets not touched in this run.
            priorClients?.forEach { (k, v) -> if (results.none { it.target.key == k }) put(k, v) }
            for (r in results) {
                if (r.latestCrc == null) continue
                putJsonObject(r.target.key) {
                    put("binaryType", r.target.binaryType)
                    put("label", r.target.label)
                    put("clientPath", r.target.clientPath)
                    put("launcherPath", r.target.launcherPath)
                    put("launcherPresent", r.launcherPresent)
                    put("serverVersion", r.serverVersion)
                    put("latestCrc", r.latestCrc)
                    r.localCrc?.let { put("localCrc", it) }
                    // Resolved status after any action this run (localCrc is updated post-install).
                    val resolved = if (r.localCrc != null && r.localCrc == r.latestCrc) Status.UP_TO_DATE else r.status
                    put("status", resolved.name)
                    put("action", r.action.name)
                    r.sizeBytes?.let { put("sizeBytes", it) }
                    r.sha256?.let { put("sha256", it) }
                    r.sourceUrl?.let { put("sourceUrl", it) }
                    if (r.action == Action.INSTALLED || r.action == Action.REPLACED) {
                        put("downloadedAt", Instant.now().toString())
                    }
                }
            }
        }
        putJsonArray("notes") {
            add("download_crc_0 is the CRC32 of the DECOMPRESSED binary; the download is LZMA-alone compressed.")
            add("Layout: data/client/<os>/{rs2client[.exe], rs3<os>, patcher-lib}. clientPath is what ConfigServer serves per OS.")
        }
    }
    manifest.parentFile?.mkdirs()
    manifest.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj))
    println("Manifest    : ${manifest.path}")
}

private fun printHelp() {
    println(
        """
        client-updater — download the latest NXT client (rs2client) per OS and check ./data/client

        Usage:
          ./gradlew :tools:run -PmainClass=org.projectx.tools.clientupdater.MainKt --args="[flags]"

        Flags:
          --dir <path>   Client root to check/update          (default ./data/client)
          --os a,b,c     Subset of {linux,windows,macos}      (default: all three)
          --check        Dry run: report status only, no downloads or writes
          --update       Replace OUTDATED existing binaries (MISSING are always filled)
          --force        Re-download every selected target even if up to date
          -h, --help     Show this help

        Per-OS layout under the client root (game client downloaded; rs3* launcher + patcher seeded
        by the cross-platform launcher):
          linux   -> linux/rs2client       linux/rs3linux        linux/libprojectx_patcher.so
          windows -> windows/rs2client.exe windows/rs3windows.exe windows/projectx_patcher.dll
          macos   -> macos/rs2client       macos/rs3mac          macos/libprojectx_patcher.dylib
        """.trimIndent(),
    )
}

/** Minimal flag/value parser: `--flag` booleans and `--key value` / `--key=value` pairs. */
private class ArgParser(args: Array<String>) {
    private val flags = mutableSetOf<String>()
    private val values = mutableMapOf<String, String>()

    init {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a.startsWith("--") && a.contains("=") -> {
                    val (k, v) = a.split("=", limit = 2)
                    values[k] = v
                }
                a == "--dir" || a == "--os" -> {
                    values[a] = args.getOrNull(i + 1).orEmpty(); i++
                }
                else -> flags += a
            }
            i++
        }
    }

    fun flag(vararg names: String) = names.any { it in flags }
    fun value(name: String): String? = values[name]?.takeIf { it.isNotBlank() }
}
