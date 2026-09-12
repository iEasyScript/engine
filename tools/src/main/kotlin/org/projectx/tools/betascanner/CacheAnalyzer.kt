package org.projectx.tools.betascanner

import org.projectx.tools.util.parseRefTable
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.compress.DecompressionContext
import java.io.BufferedWriter
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.io.Closeable

/**
 * READ-ONLY structural dump of a downloaded `js5-<index>.jcache`. It never opens a cache for
 * writing: the requested file (+ its `-wal`/`-shm` siblings, for a consistent view) is copied to
 * a throwaway temp directory and parsed from there. No network, no writes into any cache directory.
 */
class CacheAnalyzer(
    private val cacheDir: Path,
    private val maxArchives: Int = 40,
    private val maxFilesPerArchive: Int = 6,
    private val hexBytes: Int = 256,
) {

    /** Known gameval type names (strong hypothesis: index 67 has one named archive per type). */
    private val typeNames = listOf(
        "npc", "loc", "obj", "seq", "varbit", "var_player", "var_client", "component", "interface",
        "struct", "enum", "param", "inv", "graphic", "sound", "model", "material", "midi", "dbrow",
        "dbtable", "achievement", "category", "quest", "bas", "cursor", "headbar", "hitmark",
        "fontmetrics", "stylesheet", "var_clan", "var_clan_setting", "var_npc", "var_object",
        "var_player_group", "ui_anim", "ui_anim_curve",
    )

    private val ctx = DecompressionContext()

    fun analyze(index: Int, report: Path?) {
        val writer: BufferedWriter? = report?.let { Files.newBufferedWriter(it) }
        val tee = Tee(writer)
        try {
            runAnalyze(index, tee)
        } catch (e: Exception) {
            tee.line()
            tee.line("ERROR: ${e::class.simpleName}: ${e.message}")
            throw e
        } finally {
            tee.flush()
            writer?.close()
            ctx.close()
        }
        if (report != null) println("\nFull dump written to: $report")
    }

    private fun runAnalyze(index: Int, tee: Tee) {
        tee.line("==================== JS5 Cache Analyzer (read-only) ====================")
        tee.line("  Cache dir   : $cacheDir")
        tee.line("  Index       : $index  ${BetaScanner.INDEX_LABELS[index]?.let { "($it)" } ?: ""}")
        tee.line("  Mode        : ANALYZE — reads a throwaway copy, writes nothing into the cache")
        tee.line("=======================================================================")

        val jcache = cacheDir.resolve("js5-$index.jcache")
        if (!Files.exists(jcache)) {
            tee.line()
            tee.line("No js5-$index.jcache found under $cacheDir — nothing to analyze.")
            tee.line("Download it first with the scanner's --download mode, or drop an OpenRS2 export here.")
            return
        }
        tee.line("  Source file : $jcache (${Files.size(jcache)} bytes)")

        ReadOnlyDb(jcache).use { db ->
            val rawTable = readRefTableBlob(db.connection)
            if (rawTable == null) {
                tee.line()
                tee.line("Index $index has no stored reference table (cache_index KEY=1 is empty).")
                return
            }
            val decompressed = ctx.decompress(rawTable)
            if (decompressed == null) {
                tee.line()
                tee.line("Failed to decompress the reference table (${rawTable.size} container bytes).")
                return
            }

            val refTable = parseFullRefTable(decompressed)
            crossCheck(decompressed, refTable, tee)
            printRefTableHeader(refTable, rawTable.size, decompressed.size, tee)

            val gameval = buildGamevalLookup()
            printGamevalReference(gameval, tee)

            val resolution = printRefTableArchives(refTable, gameval, tee)
            dumpGroups(db.connection, refTable, gameval, tee)
            printSummary(index, refTable, resolution, db.connection, tee)
        }
    }

    /** One parsed archive entry, retaining the name-hashes + file ids that [parseRefTable] discards. */
    private data class Archive(
        val id: Int,
        val nameHash: Int,
        val crc: Int,
        val version: Int,
        val fileCount: Int,
        val fileIds: IntArray,
        val fileNameHashes: IntArray,
    )

    private data class RefTableInfo(
        val format: Int,
        val revision: Int,
        val flags: Int,
        val archives: List<Archive>,
        val bytesRemaining: Int,
    ) {
        val hasNames: Boolean get() = flags and 0x1 != 0
    }

    /**
     * Full archive-index decode (formats 5..7) that ADDITIONALLY captures the archive name-hashes,
     * per-group file ids and per-file name-hashes — the fields the canonical [parseRefTable] skips.
     * Field order mirrors the documented master/archive-index layout.
     */
    private fun parseFullRefTable(decompressed: ByteArray): RefTableInfo {
        val r = BufferReader(decompressed)
        val format = r.readUnsignedByte()
        require(format in 5..7) { "Unsupported ref-table format $format" }
        val revision = if (format >= 6) r.readInt() else 0
        val flags = r.readUnsignedByte()

        fun count(): Int = if (format >= 7) r.readBigSmart() else r.readUnsignedShort()

        val n = count()
        var prev = 0
        val ids = IntArray(n) { val id = count() + prev; prev = id; id }

        val nameHashes = IntArray(n)
        if (flags and 0x1 != 0) for (i in 0 until n) nameHashes[i] = r.readInt()

        val crcs = IntArray(n) { r.readInt() }
        if (flags and 0x8 != 0) r.skip(n * 4)
        if (flags and 0x2 != 0) r.skip(n * 64)
        if (flags and 0x4 != 0) r.skip(n * 8)
        val versions = IntArray(n) { r.readInt() }

        val fileCounts = IntArray(n) { count() }
        val fileIds = Array(n) { IntArray(fileCounts[it]) }
        for (i in 0 until n) {
            var fid = 0
            for (j in 0 until fileCounts[i]) {
                fid += count()
                fileIds[i][j] = fid
            }
        }
        val fileNameHashes = Array(n) { IntArray(0) }
        if (flags and 0x1 != 0) {
            for (i in 0 until n) fileNameHashes[i] = IntArray(fileCounts[i]) { r.readInt() }
        }

        val archives = (0 until n).map {
            Archive(ids[it], nameHashes[it], crcs[it], versions[it], fileCounts[it], fileIds[it], fileNameHashes[it])
        }
        return RefTableInfo(format, revision, flags, archives, r.remaining)
    }

    /** Sanity-cross-check the full decode against the canonical [parseRefTable]. */
    private fun crossCheck(decompressed: ByteArray, info: RefTableInfo, tee: Tee) {
        val canonical = parseRefTable(decompressed, parseFiles = true)
        if (canonical == null) {
            tee.line()
            tee.line("  cross-check: canonical parseRefTable returned null (format ${info.format}) — full decode only.")
            return
        }
        val sameCount = canonical.entries.size == info.archives.size
        val sameCrc = canonical.entries.zip(info.archives).all { (a, b) -> a.id == b.id && a.crc == b.crc && a.version == b.version }
        tee.line()
        tee.line("  cross-check vs parseRefTable: archives ${if (sameCount) "MATCH" else "MISMATCH"} (${info.archives.size}), " +
            "crc/version ${if (sameCrc) "MATCH" else "MISMATCH"}, bytesRemaining=${info.bytesRemaining} (0 == fully understood)")
    }

    private fun printRefTableHeader(info: RefTableInfo, containerBytes: Int, rawBytes: Int, tee: Tee) {
        tee.line()
        tee.line("---- Reference table (index ref) ----")
        tee.line("  container bytes : $containerBytes (compressed)  ->  $rawBytes (decompressed)")
        tee.line("  format          : ${info.format}")
        tee.line("  revision        : ${info.revision}")
        tee.line("  flags           : 0x%02x  [%s]".format(info.flags, flagNames(info.flags)))
        tee.line("  archive count   : ${info.archives.size}")
    }

    private fun flagNames(flags: Int): String {
        val parts = mutableListOf<String>()
        if (flags and 0x1 != 0) parts.add("names")
        if (flags and 0x2 != 0) parts.add("digests")
        if (flags and 0x4 != 0) parts.add("sizes")
        if (flags and 0x8 != 0) parts.add("unknown8")
        return if (parts.isEmpty()) "none" else parts.joinToString("|")
    }

    /** Per-archive ref-table listing; returns (resolved, total) archive counts. */
    private fun printRefTableArchives(info: RefTableInfo, gameval: GamevalLookup, tee: Tee): Pair<Int, Int> {
        tee.line()
        tee.line("---- Archives (first ${minOf(maxArchives, info.archives.size)} of ${info.archives.size}) ----")
        tee.line("  %-5s %-12s %-11s %-18s %-10s %-8s %s".format("id", "nameHash", "nameHash#", "type(resolved)", "version", "crc", "files"))
        tee.line("  " + "-".repeat(96))
        var resolved = 0
        for ((idx, a) in info.archives.withIndex()) {
            val match = gameval.resolve(a.nameHash)
            if (match != null) resolved++
            if (idx < maxArchives) {
                tee.line("  %-5d 0x%08x %-11d %-18s %-10d %-8d %d".format(
                    a.id, a.nameHash, a.nameHash, match?.label() ?: "-", a.version, a.crc, a.fileCount))
                if (a.fileIds.isNotEmpty()) {
                    val shown = a.fileIds.take(12).joinToString(",")
                    val more = if (a.fileIds.size > 12) " ... (+${a.fileIds.size - 12} more)" else ""
                    tee.line("        file ids: $shown$more")
                }
                if (a.fileNameHashes.isNotEmpty()) {
                    val shown = a.fileNameHashes.take(8).joinToString(",") { "0x%08x".format(it) }
                    val more = if (a.fileNameHashes.size > 8) " ... (+${a.fileNameHashes.size - 8} more)" else ""
                    tee.line("        file nameHashes: $shown$more")
                }
            }
        }
        if (info.archives.size > maxArchives) tee.line("  ... (${info.archives.size - maxArchives} more archives not listed)")
        return resolved to info.archives.size
    }

    private data class GamevalMatch(val name: String, val viaJava: Boolean, val viaCp1252: Boolean) {
        fun label(): String {
            val via = when {
                viaJava && viaCp1252 -> "java+cp1252"
                viaJava -> "java"
                viaCp1252 -> "cp1252"
                else -> "?"
            }
            return "$name[$via]"
        }
    }

    private inner class GamevalLookup(
        private val byJava: Map<Int, String>,
        private val byCp1252: Map<Int, String>,
    ) {
        fun resolve(hash: Int): GamevalMatch? {
            if (hash == 0) return null
            val j = byJava[hash]
            val c = byCp1252[hash]
            val name = j ?: c ?: return null
            return GamevalMatch(name, j != null, c != null)
        }
    }

    private fun buildGamevalLookup(): GamevalLookup {
        val byJava = HashMap<Int, String>()
        val byCp1252 = HashMap<Int, String>()
        for (name in typeNames) {
            byJava[javaNameHash(name)] = name
            byCp1252[cp1252NameHash(name)] = name
        }
        return GamevalLookup(byJava, byCp1252)
    }

    private fun printGamevalReference(gameval: GamevalLookup, tee: Tee) {
        tee.line()
        tee.line("---- Gameval type-name hash reference ----")
        tee.line("  (a) java   : hash = hash*31 + ch over chars  (== Java String.hashCode for ASCII)")
        tee.line("  (b) cp1252 : hash = (hash<<5) - hash + b over CP1252 bytes  (5-rotate variant)")
        tee.line("  %-18s %-12s %-12s %s".format("type", "java(hex)", "cp1252(hex)", "java==cp1252"))
        tee.line("  " + "-".repeat(60))
        for (name in typeNames) {
            val j = javaNameHash(name)
            val c = cp1252NameHash(name)
            tee.line("  %-18s 0x%08x   0x%08x   %s".format(name, j, c, j == c))
        }
    }

    /** jag archive/file name hash: hash = hash*31 + ch over UTF-16 chars (== Java String.hashCode for ASCII). */
    private fun javaNameHash(name: String): Int {
        var h = 0
        for (c in name) h = h * 31 + c.code
        return h
    }

    /** CP1252 5-rotate variant: hash = (hash<<5) - hash + b folding the CP1252-encoded bytes. */
    private fun cp1252NameHash(name: String): Int {
        var h = 0
        for (b in name.toByteArray(CP1252)) h = (h shl 5) - h + (b.toInt() and 0xFF)
        return h
    }

    private fun dumpGroups(conn: Connection, info: RefTableInfo, gameval: GamevalLookup, tee: Tee) {
        tee.line()
        tee.line("---- Group byte dump (first ${minOf(maxArchives, info.archives.size)} archives, " +
            "first $maxFilesPerArchive files each, first $hexBytes bytes per file) ----")
        for ((idx, a) in info.archives.withIndex()) {
            if (idx >= maxArchives) break
            val type = gameval.resolve(a.nameHash)?.label() ?: "-"
            tee.line()
            tee.line("== archive ${a.id}  type=$type  files=${a.fileCount}  nameHash=0x%08x ==".format(a.nameHash))

            val raw = readArchiveBlob(conn, a.id)
            if (raw == null) {
                tee.line("   (no stored group data for archive ${a.id} — download was partial or group is HTTP-only)")
                continue
            }
            val decompressed = ctx.decompress(raw)
            if (decompressed == null) {
                tee.line("   (failed to decompress ${raw.size} container bytes)")
                continue
            }
            val files = splitGroup(decompressed, a.fileCount, tee)
            val shown = minOf(files.size, maxFilesPerArchive)
            for (f in 0 until shown) {
                val fileId = a.fileIds.getOrElse(f) { f }
                val nameHash = a.fileNameHashes.getOrElse(f) { 0 }
                val data = files[f]
                tee.line("   -- file id=$fileId${if (nameHash != 0) " nameHash=0x%08x".format(nameHash) else ""} " +
                    "len=${data.size} --")
                hexDump(data, hexBytes, "      ", tee)
            }
            if (files.size > shown) tee.line("   ... (${files.size - shown} more files in this archive)")
        }
        if (info.archives.size > maxArchives) {
            tee.line()
            tee.line("... (${info.archives.size - maxArchives} more archives not dumped — raise --analyze cap if needed)")
        }
    }

    /**
     * Split a decompressed group container into its files using the standard JS5 framing
     * (independent of the unknown per-file payload format). Falls back to a single unsplit
     * blob on any framing error so the raw bytes are still inspectable.
     */
    private fun splitGroup(decompressed: ByteArray, fileCount: Int, tee: Tee): Array<ByteArray> {
        if (fileCount <= 1) return arrayOf(decompressed)
        return try {
            val first = decompressed[0].toInt() and 0xFF
            if (first == 1) splitModern(decompressed, fileCount) else splitLegacy(decompressed, fileCount)
        } catch (e: Exception) {
            tee.line("   (group framing split failed: ${e.message}; showing unsplit container)")
            arrayOf(decompressed)
        }
    }

    /** Modern multi-file: leading 0x01, then (N+1) int offsets truncated to 24 bits, then file data. */
    private fun splitModern(decompressed: ByteArray, fileCount: Int): Array<ByteArray> {
        val r = BufferReader(decompressed)
        r.readByte()
        val offsets = IntArray(fileCount + 1) { r.readInt() and 0xffffff }
        return Array(fileCount) { i ->
            val size = offsets[i + 1] - offsets[i]
            val data = ByteArray(size)
            r.readBytes(data, 0, size)
            data
        }
    }

    /** Legacy multi-file: trailing chunk count + per-chunk int length deltas. */
    private fun splitLegacy(decompressed: ByteArray, fileCount: Int): Array<ByteArray> {
        val r = BufferReader(decompressed)
        val raw = r.array()
        var offset = decompressed.size
        val chunks = raw[--offset].toInt() and 0xFF
        offset -= chunks * (fileCount * 4)
        val sizes = IntArray(fileCount)
        r.position(offset)
        for (c in 0 until chunks) {
            var prev = 0
            for (f in 0 until fileCount) {
                prev += r.readInt()
                sizes[f] += prev
            }
        }
        val out = Array(fileCount) { ByteArray(sizes[it]) }
        val written = IntArray(fileCount)
        var src = 0
        r.position(offset)
        for (c in 0 until chunks) {
            var len = 0
            for (f in 0 until fileCount) {
                len += r.readInt()
                System.arraycopy(raw, src, out[f], written[f], len)
                src += len
                written[f] += len
            }
        }
        return out
    }

    private fun printSummary(index: Int, info: RefTableInfo, resolution: Pair<Int, Int>, conn: Connection, tee: Tee) {
        val (resolved, total) = resolution
        val totalFiles = info.archives.sumOf { it.fileCount }
        val storedArchives = countStoredArchives(conn)

        // Heuristic: sample the first stored archive's first file and gauge how text-like it is.
        var pattern = "no sample available"
        for (a in info.archives) {
            val raw = readArchiveBlob(conn, a.id) ?: continue
            val dec = ctx.decompress(raw) ?: continue
            val files = splitGroup(dec, a.fileCount, Tee(null))
            val sample = files.firstOrNull { it.isNotEmpty() } ?: continue
            val printableRatio = sample.take(64).count { (it.toInt() and 0xFF) in 0x20..0x7E }.toDouble() / minOf(64, sample.size)
            pattern = if (printableRatio > 0.8) {
                "first file bytes are mostly ASCII (%.0f%% printable) — entries look like text/strings".format(printableRatio * 100)
            } else {
                "first file bytes are mostly binary (%.0f%% printable) — entries look like packed ints/smart-ints".format(printableRatio * 100)
            }
            break
        }

        tee.line()
        tee.line("==================== SUMMARY ====================")
        tee.line("  index            : $index ${BetaScanner.INDEX_LABELS[index]?.let { "($it)" } ?: ""}")
        tee.line("  ref-table format : ${info.format} (rev ${info.revision}), flags 0x%02x [%s]".format(info.flags, flagNames(info.flags)))
        tee.line("  archives         : ${info.archives.size} (named=${info.hasNames}, stored-with-data=$storedArchives)")
        tee.line("  name resolution  : $resolved/$total archive name-hashes matched a known gameval type")
        tee.line("  total files      : $totalFiles")
        tee.line("  byte pattern     : $pattern")
        if (info.hasNames && resolved > 0) {
            tee.line("  => index $index archives ARE named by gameval type; map each archive to its type via name-hash.")
        } else if (info.hasNames) {
            tee.line("  => index $index archives are named but none matched the known type list (extend the type names).")
        } else {
            tee.line("  => index $index archives are NOT named (flag 0x1 unset); identify archives by id/order instead.")
        }
        tee.line("================================================")
    }

    private fun countStoredArchives(conn: Connection): Int {
        return try {
            conn.prepareStatement("SELECT COUNT(*) FROM cache").use { stmt ->
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        } catch (e: SQLException) {
            0
        }
    }

    private fun readRefTableBlob(conn: Connection): ByteArray? = try {
        conn.prepareStatement("SELECT DATA FROM cache_index WHERE KEY = 1").use { stmt ->
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
        }
    } catch (e: SQLException) {
        null
    }

    private fun readArchiveBlob(conn: Connection, archive: Int): ByteArray? = try {
        conn.prepareStatement("SELECT DATA FROM cache WHERE KEY = ?").use { stmt ->
            stmt.setInt(1, archive)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
        }
    } catch (e: SQLException) {
        null
    }

    /**
     * Opens a `js5-N.jcache` strictly read-only by copying it (and any `-wal`/`-shm`) into a
     * throwaway temp directory and opening the copy. The live/beta cache is never written to.
     */
    private class ReadOnlyDb(jcache: Path) : Closeable {
        private val tempDir: Path = Files.createTempDirectory("beta-analyze")
        val connection: Connection

        init {
            val name = jcache.fileName.toString()
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = jcache.resolveSibling("$name$suffix")
                if (Files.exists(src)) {
                    Files.copy(src, tempDir.resolve("$name$suffix"), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            connection = DriverManager.getConnection("jdbc:sqlite:${tempDir.resolve(name)}")
        }

        override fun close() {
            runCatching { connection.close() }
            runCatching {
                Files.walk(tempDir).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    /** Writes each line to stdout and, when present, to a report file. */
    private class Tee(private val writer: BufferedWriter?) {
        fun line(s: String = "") {
            println(s)
            writer?.let { it.write(s); it.newLine() }
        }

        fun flush() {
            System.out.flush()
            writer?.flush()
        }
    }

    private fun hexDump(bytes: ByteArray, limit: Int, indent: String, tee: Tee) {
        if (bytes.isEmpty()) {
            tee.line("$indent(empty)")
            return
        }
        val n = minOf(bytes.size, limit)
        var off = 0
        while (off < n) {
            val hex = StringBuilder()
            val asc = StringBuilder()
            for (i in off until off + 16) {
                if (i < n) {
                    val b = bytes[i].toInt() and 0xFF
                    hex.append("%02x ".format(b))
                    asc.append(printable(b))
                } else {
                    hex.append("   ")
                }
            }
            tee.line("$indent%04x  %s |%s|".format(off, hex.toString(), asc.toString()))
            off += 16
        }
        if (bytes.size > limit) tee.line("$indent... (${bytes.size - limit} more bytes)")
    }

    private fun printable(b: Int): Char {
        if (b in 0x20..0x7E) return b.toChar()
        if (b in 0xA0..0xFF) {
            val c = String(byteArrayOf(b.toByte()), CP1252)[0]
            if (!c.isISOControl()) return c
        }
        return '.'
    }

    companion object {
        private val CP1252: Charset = Charset.forName("windows-1252")
    }
}
