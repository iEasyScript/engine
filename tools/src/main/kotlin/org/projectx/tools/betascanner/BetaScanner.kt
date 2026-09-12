package org.projectx.tools.betascanner

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.projectx.tools.cachedownloader.CacheStorage
import org.projectx.tools.cachedownloader.FileRequest
import org.projectx.tools.cachedownloader.JS5Connection
import org.projectx.tools.cachedownloader.JS5Protocol
import org.projectx.tools.cachedownloader.ProgressTracker
import org.projectx.tools.util.RefTableEntry
import org.projectx.tools.util.parseRefTable
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.secure.CRC
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/** Per-index metadata as carried by a JS5 master index (255/255). */
data class IndexMeta(val index: Int, val crc: Int, val version: Int, val files: Int, val size: Int) {
    /** A master entry is meaningful when it has a real CRC or version; 0/0 means absent/empty. */
    val populated: Boolean get() = crc != 0 || version != 0
}

/** Raised when the host refuses the offered protocol revision (handshake response != 0). */
class HandshakeRejectedException(val major: Int, val minor: Int, val code: Int) :
    RuntimeException("Host rejected JS5 handshake for version $major.$minor (response code $code)")

data class BetaScannerConfig(
    val host: String,
    val port: Int,
    val major: Int,
    val minor: Int,
    val token: String,
    val outDir: Path,
    val liveDir: Path,
    val connections: Int,
)

/**
 * Connects to a JS5 server (live or beta), enumerates the indices it advertises, diffs
 * them read-only against our live cache, and optionally downloads selected indices into an
 * ISOLATED output directory. It never opens the live cache for writing — the diff only
 * reads a throwaway copy of the live master index.
 */
class BetaScanner(private val config: BetaScannerConfig) {

    companion object {
        /** Indices Jagex serves only over HTTP (not the JS5 TCP path) — skip for TCP download. */
        val HTTP_INDICES = setOf(40)

        /** Display labels for well-known indices (purely cosmetic for the report). */
        val INDEX_LABELS = mapOf(
            2 to "configs", 3 to "interfaces", 5 to "maps", 7 to "old-models", 8 to "graphics",
            12 to "clientscripts", 40 to "music(HTTP)", 47 to "models", 48 to "anim-frames",
            52 to "textures-dds", 53 to "textures-png", 54 to "textures-bmp", 55 to "textures-ktx",
            67 to "gameval/RSCM",
        )

        private const val MASTER_INDEX = 255
        private const val MAX_REQUESTS_PER_CONNECTION = 25

        init {
            // The sqlite-jdbc driver normally auto-registers, but force it so the live-master
            // copy read works regardless of classloader quirks.
            runCatching { Class.forName("org.sqlite.JDBC") }
        }
    }

    private fun label(index: Int): String = INDEX_LABELS[index] ?: ""

    fun scan() {
        val ctx = DecompressionContext()
        val (sock, inp, out) = openControl()
        val serverMaster: List<IndexMeta>
        try {
            serverMaster = fetchServerMaster(inp, out, ctx)
        } finally {
            closeQuietly(sock)
        }

        val liveMaster = readLiveMasterCopy(ctx)
        val present = liveIndexFilesPresent()

        printServerTable(serverMaster, liveMaster, present)
        val missing = printDiff(serverMaster, liveMaster, present)

        println()
        if (missing.isEmpty()) {
            println("Diff: no server indices are missing/empty in the live cache.")
        } else {
            println("Diff: ${missing.size} server index(es) missing/empty in live: ${missing.joinToString(",")}")
            println("  -> 'missing' download selector would fetch these into ${config.outDir}")
        }
    }

    suspend fun download(selector: String) {
        val ctx = DecompressionContext()
        val storage = CacheStorage(config.outDir)
        val progress = ProgressTracker()
        try {
            var ctrl = openControl()
            val serverMaster = try {
                fetchServerMaster(ctrl.second, ctrl.third, ctx)
            } catch (e: Exception) {
                closeQuietly(ctrl.first); throw e
            }

            val liveMaster = readLiveMasterCopy(ctx)
            val requested = resolveTargets(selector, serverMaster, liveMaster)
            val tcpTargets = requested.filter { idx ->
                when {
                    idx == MASTER_INDEX -> { println("  skipping index 255 (master, not a downloadable archive group)"); false }
                    idx in HTTP_INDICES -> { println("  skipping index $idx (${label(idx)}): HTTP-only, not served over JS5 TCP"); false }
                    serverMaster.none { it.index == idx && it.populated } -> {
                        println("  skipping index $idx: not advertised/populated by $host"); false
                    }
                    else -> true
                }
            }

            if (tcpTargets.isEmpty()) {
                println("Nothing to download for selector '$selector'.")
                closeQuietly(ctrl.first)
                return
            }
            println("Resolved targets ($selector): ${tcpTargets.joinToString(",")}")
            println("Phase 1: downloading ref tables...")

            val entriesByIndex = LinkedHashMap<Int, List<RefTableEntry>>()
            var reqs = 1 // master index already consumed one request on this connection
            for (idx in tcpTargets) {
                if (reqs >= MAX_REQUESTS_PER_CONNECTION) {
                    closeQuietly(ctrl.first)
                    ctrl = openControl()
                    reqs = 0
                }
                val entries = try {
                    fetchRefTable(ctrl.second, ctrl.third, idx, ctx, storage)
                } catch (e: Exception) {
                    closeQuietly(ctrl.first)
                    ctrl = openControl(); reqs = 0
                    try {
                        fetchRefTable(ctrl.second, ctrl.third, idx, ctx, storage)
                    } catch (e2: Exception) {
                        println("  index $idx: ref table failed (${e2.message})"); null
                    }
                }
                reqs++
                if (entries != null) {
                    entriesByIndex[idx] = entries
                    println("  index $idx (${label(idx)}): ${entries.size} archives")
                }
            }
            closeQuietly(ctrl.first)

            val queue = Channel<FileRequest>(Channel.UNLIMITED)
            var total = 0
            for ((idx, entries) in entriesByIndex) {
                for (e in entries) {
                    queue.send(FileRequest(idx, e.id, e.version, e.crc))
                    total++
                }
            }
            queue.close()
            progress.totalFiles.set(total)

            if (total == 0) {
                println("No archives to download (ref tables were empty).")
                return
            }

            println("Phase 2: downloading $total archives with ${config.connections} connection(s)...")
            coroutineScope {
                repeat(config.connections) { id ->
                    launch {
                        JS5Connection(
                            id, host, config.port, config.major, config.minor,
                            config.token, queue, storage, progress
                        ).run()
                    }
                }
                launch {
                    while (isActive && (progress.completedFiles.get() + progress.failedFiles.get()) < total) {
                        progress.report()
                        delay(2000)
                    }
                    progress.report()
                }
            }
            progress.summary()
            println("Beta download complete -> ${config.outDir}")
        } finally {
            storage.close()
        }
    }

    private fun resolveTargets(
        selector: String,
        serverMaster: List<IndexMeta>,
        liveMaster: Map<Int, IndexMeta>?,
    ): List<Int> {
        val populated = serverMaster.filter { it.populated }.map { it.index }
        return when (selector.lowercase()) {
            "all" -> populated
            "missing" -> populated.filter { idx ->
                val live = liveMaster?.get(idx)
                live == null || !live.populated
            }
            else -> selector.split(",").mapNotNull { it.trim().toIntOrNull() }.distinct()
        }
    }

    private val host: String get() = config.host
    private val port: Int get() = config.port
    private val major: Int get() = config.major
    private val minor: Int get() = config.minor
    private val token: String get() = config.token

    /** Connect + handshake at the configured revision. Throws on rejection — never retries. */
    private fun openControl(): Triple<Socket, DataInputStream, DataOutputStream> {
        val sock = Socket(host, port)
        sock.soTimeout = 30_000
        val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 64 * 1024))
        val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 1024 * 1024))
        val response = JS5Protocol.handshake(out, inp, major, minor, token)
        if (response != 0) {
            closeQuietly(sock)
            throw HandshakeRejectedException(major, minor, response)
        }
        JS5Protocol.sendConnectionInit(out, major)
        return Triple(sock, inp, out)
    }

    private fun fetchServerMaster(
        inp: DataInputStream, out: DataOutputStream, ctx: DecompressionContext,
    ): List<IndexMeta> {
        JS5Protocol.sendFileRequest(out, MASTER_INDEX, MASTER_INDEX, major)
        val response = JS5Protocol.readResponse(inp)
        val decompressed = ctx.decompress(response.container)
            ?: throw IllegalStateException("Failed to decompress server master index")
        return parseMaster(decompressed)
    }

    private fun fetchRefTable(
        inp: DataInputStream, out: DataOutputStream,
        index: Int, ctx: DecompressionContext, storage: CacheStorage,
    ): List<RefTableEntry>? {
        JS5Protocol.sendFileRequest(out, MASTER_INDEX, index, major)
        val response = JS5Protocol.readResponse(inp)
        val crc = CRC.calculate(response.container)
        storage.storeRefTable(index, response.container, 0, crc)
        return parseRefTable(ctx, response.container)?.entries
    }

    /** Parse a decompressed master index into per-index metadata (ignores the trailing RSA signature). */
    private fun parseMaster(decompressed: ByteArray): List<IndexMeta> {
        val reader = BufferReader(decompressed)
        val count = reader.readUnsignedByte()
        return List(count) { i ->
            val crc = reader.readInt()
            val version = reader.readInt()
            val files = reader.readInt()
            val size = reader.readInt()
            reader.skip(64)
            IndexMeta(i, crc, version, files, size)
        }
    }

    /** Index numbers for which a `js5-N.jcache` file physically exists in the live dir. */
    private fun liveIndexFilesPresent(): Set<Int> {
        val dir = config.liveDir
        if (!Files.isDirectory(dir)) return emptySet()
        val regex = Regex("""js5-(\d+)\.jcache""")
        val result = mutableSetOf<Int>()
        Files.newDirectoryStream(dir, "js5-*.jcache").use { stream ->
            for (path in stream) {
                regex.matchEntire(path.fileName.toString())?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { result.add(it) }
            }
        }
        return result
    }

    /**
     * Reads the live master index without ever opening the live SQLite files for writing:
     * the live `js5-255.jcache` (and its WAL/SHM if present, for a consistent view) is copied
     * to a throwaway temp directory and parsed from there. Returns null when there is no live
     * master to compare against.
     */
    private fun readLiveMasterCopy(ctx: DecompressionContext): Map<Int, IndexMeta>? {
        val masterFile = config.liveDir.resolve("js5-$MASTER_INDEX.jcache")
        if (!Files.exists(masterFile)) return null

        val tempDir = Files.createTempDirectory("beta-live-master")
        try {
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = config.liveDir.resolve("js5-$MASTER_INDEX.jcache$suffix")
                if (Files.exists(src)) {
                    Files.copy(src, tempDir.resolve("js5-$MASTER_INDEX.jcache$suffix"), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val copy = tempDir.resolve("js5-$MASTER_INDEX.jcache")
            val container = DriverManager.getConnection("jdbc:sqlite:$copy").use { conn ->
                conn.prepareStatement("SELECT DATA FROM cache_index WHERE KEY = 1").use { stmt ->
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
                }
            } ?: return emptyMap()
            val decompressed = ctx.decompress(container) ?: return emptyMap()
            return parseMaster(decompressed).associateBy { it.index }
        } catch (e: Exception) {
            System.err.println("  warning: could not read live master index (${e.message}); diff will treat live as empty")
            return emptyMap()
        } finally {
            deleteRecursive(tempDir)
        }
    }

    private fun liveStatus(meta: IndexMeta, liveMaster: Map<Int, IndexMeta>?, present: Set<Int>): String {
        if (!meta.populated) return "server-empty"
        val live = liveMaster?.get(meta.index)
        val fileNote = if (meta.index !in present) ", no-file" else ""
        return when {
            live == null || !live.populated -> "MISSING/EMPTY$fileNote"
            live.crc != meta.crc -> "CRC-DELTA(live=${live.crc} v${live.version})$fileNote"
            live.size != meta.size -> "SIZE-DELTA(live=${live.size})$fileNote"
            live.version != meta.version -> "version-only(live v${live.version})$fileNote"
            else -> "in-sync"
        }
    }

    private fun printServerTable(serverMaster: List<IndexMeta>, liveMaster: Map<Int, IndexMeta>?, present: Set<Int>) {
        println()
        println("Server indices advertised by $host:${config.port} (rev ${config.major}.${config.minor}):")
        println("  %-4s %-15s %-12s %-10s %-8s %-12s %s".format("idx", "label", "crc", "version", "files", "size", "live-status"))
        println("  " + "-".repeat(86))
        for (meta in serverMaster) {
            val status = liveStatus(meta, liveMaster, present)
            val line = "  %-4d %-15s %-12d %-10d %-8d %-12d %s".format(
                meta.index, label(meta.index), meta.crc, meta.version, meta.files, meta.size, status
            )
            println(line)
        }
    }

    /** Prints the diff, highlights index 67 explicitly, and returns the missing/empty index list. */
    private fun printDiff(
        serverMaster: List<IndexMeta>, liveMaster: Map<Int, IndexMeta>?, present: Set<Int>,
    ): List<Int> {
        if (liveMaster == null) {
            println()
            println("No live cache master found under ${config.liveDir} — cannot diff; showing server view only.")
        }
        val missing = serverMaster.filter { meta ->
            meta.populated && (liveMaster?.get(meta.index)?.populated != true)
        }.map { it.index }

        val sixtySeven = serverMaster.find { it.index == 67 }
        if (sixtySeven != null && sixtySeven.populated) {
            val liveSeven = liveMaster?.get(67)
            println()
            if (liveSeven == null || !liveSeven.populated) {
                println("*** INDEX 67 (${label(67)}): present on $host (crc=${sixtySeven.crc} v${sixtySeven.version}, " +
                    "${sixtySeven.files} files, ${sixtySeven.size} bytes) but EMPTY/MISSING in the live cache. ***")
            } else {
                println("Index 67 (${label(67)}): present on server AND populated in live (crc live=${liveSeven.crc} server=${sixtySeven.crc}).")
            }
        } else {
            println()
            println("Index 67 (${label(67)}): not advertised/populated by $host.")
        }
        return missing
    }

    private fun closeQuietly(sock: Socket) {
        try { sock.close() } catch (_: Exception) {}
    }

    private fun deleteRecursive(dir: Path) {
        runCatching {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
