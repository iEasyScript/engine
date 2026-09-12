package org.projectx.tools.util

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.compress.DecompressionContext

data class RefTableEntry(
    val id: Int,
    val crc: Int,
    val version: Int,
    val fileCount: Int = 0,
    val nameHash: Int = 0,
)

/**
 * Parsed JS5 reference table (archive index). Single canonical decoder for the
 * cache downloader, integrity checker, snapshot/diff tooling and index verification tools.
 */
data class RefTable(
    val format: Int,
    val revision: Int,
    val flags: Int,
    val entries: List<RefTableEntry>,
    val totalFileCount: Int,
    val maxGroupId: Int,
    val bytesRemaining: Int,
    val fileIds: Map<Int, IntArray> = emptyMap(),
    val fileNameHashes: Map<Int, IntArray> = emptyMap(),
) {
    val named: Boolean get() = flags and NAMES != 0

    fun crcById(): Map<Int, Int> = entries.associate { it.id to it.crc }
    fun versionById(): Map<Int, Int> = entries.associate { it.id to it.version }
    fun entryById(): Map<Int, RefTableEntry> = entries.associateBy { it.id }
}

const val NAMES = 0x1
private const val WHIRLPOOL = 0x2
private const val SIZES = 0x4
private const val UNKNOWN_HASHES = 0x8

/** Decompress [rawTable] then parse it. Returns null on decompression failure or unknown format. */
fun parseRefTable(context: DecompressionContext, rawTable: ByteArray, parseFiles: Boolean = false): RefTable? {
    val decompressed = context.decompress(rawTable) ?: return null
    return parseRefTable(decompressed, parseFiles)
}

/**
 * Parse an already-decompressed reference table (formats 5..7).
 *
 * With [parseFiles] the per-group file counts, delta-encoded file IDs and (flag [NAMES])
 * file name hashes are consumed as well, so [RefTable.bytesRemaining] == 0 proves the
 * whole table was understood (used by FullIndexTest).
 */
fun parseRefTable(decompressed: ByteArray, parseFiles: Boolean = false): RefTable? {
    val reader = BufferReader(decompressed)

    val format = reader.readUnsignedByte()
    if (format < 5 || format > 7) return null
    val revision = if (format >= 6) reader.readInt() else 0

    val flags = reader.readUnsignedByte()
    fun count(): Int = if (format >= 7) reader.readBigSmart() else reader.readUnsignedShort()

    val archiveCount = count()

    var previous = 0
    var maxId = -1
    val ids = IntArray(archiveCount) {
        val id = count() + previous
        previous = id
        if (id > maxId) maxId = id
        id
    }

    val nameHashes = if (flags and NAMES != 0) IntArray(archiveCount) { reader.readInt() } else IntArray(archiveCount)
    val crcs = IntArray(archiveCount) { reader.readInt() }
    if (flags and UNKNOWN_HASHES != 0) reader.skip(archiveCount * 4)
    if (flags and WHIRLPOOL != 0) reader.skip(archiveCount * 64)
    if (flags and SIZES != 0) reader.skip(archiveCount * 8)
    val versions = IntArray(archiveCount) { reader.readInt() }

    var totalFiles = 0
    val fileCounts = IntArray(archiveCount)
    val fileIds = HashMap<Int, IntArray>(if (parseFiles) archiveCount else 0)
    val fileNameHashes = HashMap<Int, IntArray>(0)
    if (parseFiles) {
        for (i in 0 until archiveCount) {
            val fc = count()
            fileCounts[i] = fc
            totalFiles += fc
        }
        for (i in 0 until archiveCount) {
            var previousFile = 0
            fileIds[ids[i]] = IntArray(fileCounts[i]) {
                val file = count() + previousFile
                previousFile = file
                file
            }
        }
        if (flags and NAMES != 0) {
            for (i in 0 until archiveCount) {
                fileNameHashes[ids[i]] = IntArray(fileCounts[i]) { reader.readInt() }
            }
        }
    }

    val entries = List(archiveCount) { i -> RefTableEntry(ids[i], crcs[i], versions[i], fileCounts[i], nameHashes[i]) }
    return RefTable(format, revision, flags, entries, totalFiles, maxId, reader.remaining, fileIds, fileNameHashes)
}
