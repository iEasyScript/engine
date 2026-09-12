package org.projectx.tools.gamevalexport

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.gameval.GamevalIndex
import world.gregs.voidps.cache.gameval.GamevalIndexDecoder
import world.gregs.voidps.cache.sqlite.SQLiteCache
import world.gregs.voidps.gameval.ComponentAlignmentStamp
import world.gregs.voidps.gameval.GamevalAlignment
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * Generates our own prettified gameval JSON from a real cache **index 67** (gameval / RSCM), decoded
 * via [GamevalIndexDecoder]. One `<type>.json` per gameval type, sorted by id, with metadata. Reads a
 * cache dir READ-ONLY (default `./data/betacache`, fetched by `betaScanner --download 67`) and never
 * touches the live cache.
 *
 * Each combined `var_*` archive is split into `var_<domain>.json` (the vars) AND `varbit_<domain>.json`
 * (the varbits) — the varbit file is skipped for domains with no varbits (`var_client`,
 * `var_player_group`). The legacy combined `varbit.json` is deleted (replaced by the per-domain
 * varbit files). Other, non-index-67 files in the output dir are left untouched.
 *
 * Run: `./gradlew :tools:gamevalExport -Pargs="--cache ./data/betacache --out re-resources/gamevals"`
 */
@OptIn(ExperimentalSerializationApi::class)
fun main(args: Array<String>) {
    var cacheDir = "./data/betacache"
    var outDir = "re-resources/gamevals"
    var revision = 949
    var source = "content.beta.runescape.com"
    var alignCacheDir: String? = null

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--cache" -> cacheDir = args[++i]
            "--out" -> outDir = args[++i]
            "--revision" -> revision = args[++i].toInt()
            "--source" -> source = args[++i]
            "--align" -> alignCacheDir = args[++i]
            "--help", "-h" -> { printUsage(); return }
            else -> { System.err.println("Unknown arg: $a"); printUsage(); exitProcess(2) }
        }
        i++
    }

    val cachePath = Path.of(cacheDir).toAbsolutePath().normalize()
    val indexFile = cachePath.resolve("js5-${GamevalIndex.INDEX}.jcache")
    if (!indexFile.exists()) {
        System.err.println("No $indexFile — fetch index 67 first: ./gradlew :tools:betaScanner -Pargs=\"--host content.beta.runescape.com --major 949 --token <t> --download 67\"")
        exitProcess(1)
    }
    val out = Path.of(outDir).toAbsolutePath().normalize()
    Files.createDirectories(out)

    println("==================== Gameval JSON export ====================")
    println("  cache (read-only) : $cachePath")
    println("  out               : $out")
    println("  revision / source : $revision / $source")
    alignCacheDir?.let { println("  align against     : ${Path.of(it).toAbsolutePath().normalize()}") }
    println("=============================================================")

    val cache = SQLiteCache.load(cachePath, readOnly = true)
    val decoder = GamevalIndexDecoder()
    val all = decoder.decode(cache)
    val (components, alignment) = alignComponents(decoder.decodeComponents(cache), cache, alignCacheDir)
    val date = LocalDate.now().toString()
    val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    var totalEntries = 0
    var filesWritten = 0

    fun writeType(type: String, archive: Int, sorted: List<Pair<String, String>>) {
        val obj = buildJsonObject {
            put("revision", revision)
            put("source", "$source cache index ${GamevalIndex.INDEX} archive $archive")
            put("date", date)
            if (type == GamevalIndex.COMPONENT && alignment != null) {
                put("alignment", json.encodeToJsonElement(ComponentAlignmentStamp.serializer(), alignment))
            }
            putJsonObject("entries") { for ((k, v) in sorted) put(k, v) }
        }
        Files.writeString(out.resolve("$type.json"), json.encodeToString(JsonObject.serializer(), obj))
        println("  wrote %-20s %7d entries".format(type, sorted.size))
        totalEntries += sorted.size
        filesWritten++
    }

    for ((archive, type) in GamevalIndex.TYPE_BY_ARCHIVE) {
        val sorted: List<Pair<String, String>> = if (type == GamevalIndex.COMPONENT) {
            components.entries
                .sortedWith(compareBy({ it.key.substringBefore(':').toIntOrNull() ?: 0 }, { it.key.substringAfter(':').toIntOrNull() ?: 0 }))
                .map { it.key to it.value }
        } else {
            // For combined var_* archives this is the split var-part (see GamevalIndexDecoder.decode).
            (all[type] ?: emptyMap()).entries.sortedBy { it.key }.map { it.key.toString() to it.value }
        }
        writeType(type, archive, sorted)

        // Combined var archives also yield a per-domain varbit table; write it when non-empty.
        val domain = GamevalIndex.VAR_DOMAIN_BY_ARCHIVE[archive]
        if (domain != null) {
            val varbits = all[domain.varbitType] ?: emptyMap()
            if (varbits.isNotEmpty()) {
                writeType(domain.varbitType, archive, varbits.entries.sortedBy { it.key }.map { it.key.toString() to it.value })
            }
        }
    }

    // The old combined varbit.json is superseded by the per-domain varbit_<domain>.json files.
    if (Files.deleteIfExists(out.resolve("varbit.json"))) {
        println("  removed obsolete varbit.json (replaced by varbit_<domain>.json)")
    }

    println("Done: $filesWritten files, $totalEntries entries -> $out")
}

private data class AlignedComponents(val entries: Map<String, String>, val stamp: ComponentAlignmentStamp?)

/**
 * Rewrites beta component keys ("betaInterface:betaSlot") to the slots the server's own cache uses,
 * so a beta-only insertion cannot shift every name after it. Beta components with no counterpart in
 * the server cache are dropped, and the served interface index is stamped so [GamevalAlignment] can
 * tell when a later download has moved it. Without [alignCacheDir] the beta numbering is kept as-is.
 */
private fun alignComponents(
    components: Map<String, String>,
    betaCache: Cache,
    alignCacheDir: String?,
): AlignedComponents {
    if (alignCacheDir == null) return AlignedComponents(components, null)
    val path = Path.of(alignCacheDir).toAbsolutePath().normalize()
    if (!path.resolve("js5-${Index.INTERFACES}.jcache").exists()) {
        System.err.println("No js5-${Index.INTERFACES}.jcache in $path — cannot align; keeping beta slots")
        return AlignedComponents(components, null)
    }

    val serverCache = SQLiteCache.load(path, readOnly = true)
    val result = ComponentAlignment(serverCache, betaCache).align()
    val stamp = ComponentAlignmentStamp(
        interfacesCrc = GamevalAlignment.servedInterfacesCrc(serverCache) ?: 0,
        unaligned = result.unalignable.map { it.interfaceId }.sorted(),
    )

    val aligned = LinkedHashMap<String, String>(components.size)
    var dropped = 0
    for ((key, name) in components) {
        val interfaceId = key.substringBefore(':').toInt()
        val slot = key.substringAfter(':').toInt()
        val remap = result.remapped[interfaceId]
        if (remap == null) {
            aligned[key] = name
            continue
        }
        val serverSlot = remap[slot]
        if (serverSlot == null) dropped++ else aligned["$interfaceId:$serverSlot"] = name
    }

    println("  aligned ${result.remapped.size} interface(s) to the server cache, dropped $dropped beta-only component name(s)")
    for ((interfaceId, remap) in result.remapped) {
        val shifted = remap.count { (betaSlot, serverSlot) -> betaSlot != serverSlot }
        println("    interface %-6d %4d names remapped, %4d shifted".format(interfaceId, remap.size, shifted))
    }
    if (result.unalignable.isNotEmpty()) {
        println("  NOT aligned (beta rebuilt these — their component ids do NOT match the server cache):")
        for (u in result.unalignable) {
            println("    interface %-6d server %4d vs beta %4d components, best shape agreement %.0f%%"
                .format(u.interfaceId, u.serverCount, u.betaCount, u.agreement * 100))
        }
    }
    println("  stamped served interface index crc ${stamp.interfacesCrc}")
    return AlignedComponents(aligned, stamp)
}

private fun printUsage() {
    println(
        """
        Gameval JSON exporter — decode cache index ${GamevalIndex.INDEX} (gameval/RSCM) into prettified per-type JSON.
          --cache <dir>     cache dir holding js5-${GamevalIndex.INDEX}.jcache (default ./data/betacache; read-only)
          --out <dir>       output dir for <type>.json (default re-resources/gamevals)
          --revision <n>    revision stamped into the json (default 949)
          --source <s>      source stamped into the json (default content.beta.runescape.com)
          --align <dir>     re-key component ids onto this cache's interface layout, read-only
                            (e.g. ./data/cache — the cache the server actually serves)
        Each combined var_* archive is split into var_<domain>.json + varbit_<domain>.json; the legacy
        combined varbit.json is removed. Other, non-index-${GamevalIndex.INDEX} files are left untouched.
        """.trimIndent(),
    )
}
