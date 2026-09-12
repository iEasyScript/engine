package org.projectx.tools.cachesource

import org.projectx.core.EnvVars
import org.projectx.tools.betascanner.IsolationGuard
import world.gregs.voidps.cache.source.Builder
import world.gregs.voidps.cache.source.CacheEra
import world.gregs.voidps.cache.source.Packer
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.source.UnpackOptions
import world.gregs.voidps.cache.source.Unpacker
import world.gregs.voidps.cache.source.Verifier
import world.gregs.voidps.cache.source.XteaKeys
import world.gregs.voidps.cache.source.codec.cs2.Cs2SourceCodec
import world.gregs.voidps.cache.store.CacheStore
import world.gregs.voidps.cache.store.Converter
import world.gregs.voidps.cache.store.StoreKind
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = """
Cache source tree - unpack a packed cache into one file per asset, and pack it back byte for byte.

  cacheSource unpack  [--cache <dir>] [--tree <dir>] [--revision <n>] [--indices <sel>]
                      [--passthrough <sel>] [--opaque <sel>] [--xteas <file>] [--gamevals <dir>] [--threads <n>]
  cacheSource pack    [--tree <dir>] --out <dir> [--from <cache>] [--store sqlite|sector]
  cacheSource build   [--tree <dir>] --out <dir> [--from <cache>]
  cacheSource verify  [--tree <dir>] [--cache <dir>]
  cacheSource status  [--tree <dir>]
  cacheSource convert --cache <dir> --out <dir> [--store sqlite|sector]

  --cache <dir>        the packed cache, opened READ-ONLY            (default ./data/cache)
  --tree <dir>         the source tree                               (default ./unpacked-cache)
  --revision <n>       the client build the cache belongs to; detected from the store when absent
  --indices <sel>      only these indices: a comma list and/or a-b ranges
  --passthrough <sel>  leave these indices out of the tree; `pack --from` copies them verbatim
  --opaque <sel>       keep these indices' archives as unopened containers (exact, unreadable)
  --xteas <file>       a legacy xteaKeys.json for the encrypted map archives
  --gamevals <dir>     catalogs to seed gamevals/ from when the cache has no gameval index
                                                                     (default ${'$'}GAMEVAL_PATH)
  --from <cache>       the cache passthrough indices are copied from (default --cache)
  --store <kind>       the store to build, the tree's own by default
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println(USAGE.trimIndent())
        return
    }
    val command = args[0]
    val options = parse(args.drop(1))
    val cacheDir = Paths.get(options["cache"] ?: "./data/cache").toAbsolutePath().normalize()
    val treeDir = Paths.get(options["tree"] ?: "./unpacked-cache").toAbsolutePath().normalize()
    Cs2SourceCodec.cachePath = cacheDir
    when (command) {
        "unpack" -> unpack(cacheDir, treeDir, options)
        "pack" -> pack(treeDir, options, incremental = false)
        "build" -> pack(treeDir, options, incremental = true)
        "verify" -> verify(treeDir, cacheDir)
        "status" -> status(treeDir)
        "convert" -> convert(cacheDir, options)
        else -> {
            System.err.println("Unknown command: $command")
            println(USAGE.trimIndent())
            exitProcess(64)
        }
    }
}

private fun parse(args: List<String>): Map<String, String> {
    val options = HashMap<String, String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        if (!arg.startsWith("--")) {
            System.err.println("Unexpected argument: $arg")
            exitProcess(64)
        }
        val key = arg.removePrefix("--")
        val value = args.getOrNull(index + 1)
        if (value == null || value.startsWith("--")) {
            options[key] = "true"
            index++
        } else {
            options[key] = value
            index += 2
        }
    }
    return options
}

private fun selection(selector: String?): Set<Int>? {
    if (selector == null || selector.equals("all", ignoreCase = true)) {
        return null
    }
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

private fun guard(path: Path) {
    try {
        IsolationGuard.check(path)
    } catch (e: IsolationGuard.ForbiddenOutputException) {
        System.err.println(e.message)
        exitProcess(3)
    }
}

/** The revision a cache belongs to when nobody said: the legacy build for a sector store, the client's build otherwise. */
private fun detectRevision(store: CacheStore): Int = when (store.kind) {
    StoreKind.SECTOR -> LEGACY_REVISION
    StoreKind.SQLITE -> NXT_REVISION
}

private const val LEGACY_REVISION = 727
private const val NXT_REVISION = 949

private fun unpack(cacheDir: Path, treeDir: Path, options: Map<String, String>) {
    guard(treeDir)
    val store = CacheStore.open(cacheDir, writable = false)
    val revision = options["revision"]?.toInt() ?: detectRevision(store)
    val passthrough = selection(options["passthrough"]) ?: emptySet()
    val existing = if (java.nio.file.Files.isRegularFile(treeDir.resolve(SourceTree.CACHE_FILE))) SourceTree.open(treeDir) else null
    val indexCount = maxOf(store.indexCount(), existing?.indexCount ?: 0)
    val tree = SourceTree.create(treeDir, revision, store.kind, indexCount, (existing?.passthrough ?: emptySet()) + passthrough)
    val gamevals = options["gamevals"]?.let { Paths.get(it) } ?: Paths.get(EnvVars.gamevalPath)
    val xteas = options["xteas"]?.let { XteaKeys.regions(Paths.get(it)) }
        ?: if (CacheEra.of(revision) == CacheEra.LEGACY) XteaKeys.regions(cacheDir.resolveSibling("map").resolve("xteaKeys.json")) else XteaKeys.NONE
    println("==================== Cache unpack ====================")
    println("  cache (read-only) : $cacheDir (${store.kind.id})")
    println("  tree              : $treeDir")
    println("  revision          : $revision (${tree.era.name.lowercase()})")
    if (tree.passthrough.isNotEmpty()) println("  passthrough       : ${tree.passthrough.sorted()}")
    println("======================================================")
    val report = try {
        Unpacker.unpack(
            store,
            tree,
            UnpackOptions(
                indices = selection(options["indices"]),
                passthrough = tree.passthrough,
                opaque = selection(options["opaque"]) ?: emptySet(),
                keys = xteas,
                gamevals = gamevals,
                threads = options["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
            )
        ) { println("  $it") }
    } finally {
        store.close()
    }
    println()
    println("  indices     : ${report.indices}")
    println("  archives    : ${report.archives}")
    println("  files       : ${report.files} (${report.bytes} bytes)")
    println("  written     : ${report.written}")
    println("  deleted     : ${report.deleted}")
    println("  opaque      : ${report.opaque}")
    println("  pristine    : ${report.pristine} files, ${report.inexact} archives")
    println("  payloads    : ${report.payloads}")
    println("  verbatim    : ${report.verbatim}")
    println("  missing     : ${report.missing}")
    println("  passthrough : ${report.passthrough} indices")
    println("  time        : ${report.millis} ms")
}

private fun pack(treeDir: Path, options: Map<String, String>, incremental: Boolean) {
    val output = Paths.get(options["out"] ?: run {
        System.err.println("--out <dir> is required")
        exitProcess(64)
    }).toAbsolutePath().normalize()
    guard(output)
    val tree = SourceTree.open(treeDir)
    val kind = options["store"]?.let { StoreKind.of(it) } ?: tree.store
    val from = options["from"] ?: options["cache"] ?: "./data/cache"
    val passthrough = if (tree.passthrough.isEmpty()) null else CacheStore.open(Paths.get(from).toAbsolutePath().normalize())
    try {
        println("${if (incremental) "Building" else "Packing"} $tree into $output (${kind.id})")
        if (incremental) {
            val result = Builder.build(tree, output, passthrough) { println("  $it") }
            println()
            println("  ${result}")
        } else {
            val result = Packer.pack(tree, output, passthrough, kind) { println("  $it") }
            println()
            println("  indices     : ${result.indices}")
            println("  archives    : ${result.archives}")
            println("  bytes       : ${result.bytes}")
            println("  passthrough : ${result.passthrough}")
            println("  time        : ${result.millis} ms")
        }
    } finally {
        passthrough?.close()
    }
}

private fun verify(treeDir: Path, cacheDir: Path) {
    val tree = SourceTree.open(treeDir)
    val store = CacheStore.open(cacheDir)
    println("Verifying $tree against $cacheDir")
    val report = try {
        Verifier.verify(tree, store) { println("  $it") }
    } finally {
        store.close()
    }
    println()
    println("  $report")
    if (!report.parity) {
        exitProcess(1)
    }
}

private fun status(treeDir: Path) {
    val tree = SourceTree.open(treeDir)
    val status = tree.status()
    println("$tree")
    for (index in status.indices) {
        println("  $index")
        for (stale in index.stale.take(10)) {
            println("      stale: $stale")
        }
    }
    println()
    println("  $status")
    println("  gamevals: ${if (status.gamevals < 0) "none" else "${status.gamevals} catalogs"}")
}

private fun convert(cacheDir: Path, options: Map<String, String>) {
    val output = Paths.get(options["out"] ?: run {
        System.err.println("--out <dir> is required")
        exitProcess(64)
    }).toAbsolutePath().normalize()
    guard(output)
    val source = CacheStore.open(cacheDir)
    val kind = options["store"]?.let { StoreKind.of(it) } ?: when (source.kind) {
        StoreKind.SECTOR -> StoreKind.SQLITE
        StoreKind.SQLITE -> StoreKind.SECTOR
    }
    println("Converting $cacheDir (${source.kind.id}) into $output (${kind.id})")
    val result = try {
        Converter.convert(source, output, kind) { println("  $it") }
    } finally {
        source.close()
    }
    println()
    println("  $result")
}
