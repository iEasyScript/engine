package org.projectx.tools

import org.projectx.tools.util.parseRefTable
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.sqlite.IndexFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The cache downloader stores containers WITHOUT a 2-byte version suffix. The ref table CRCs from
 * Jagex are computed over (container - 2 byte version suffix), so CRC.calculate(stored_data) should
 * match the ref table CRC.
 */

private const val COMPRESSION_NONE = 0
private const val COMPRESSION_BZIP2 = 1
private const val COMPRESSION_GZIP = 2
private const val COMPRESSION_LZMA = 3

data class IntegrityResult(
    val index: Int,
    val archive: Int,
    val dataSize: Int,
    val compression: Int,
    val compressedSize: Int,
    val decompressedSize: Int,
    val expectedPayloadSize: Int,
    val actualPayloadSize: Int,
    val truncated: Boolean,
    val oversized: Boolean,
    val extraBytes: Int,
    val decompressionOk: Boolean?,
    val decompressionError: String?,
    val crcMatch: Boolean?,
    val storedCrc: Int?,
    val computedCrc: Int,
    val refTableCrc: Int?
)

fun main() {
    val cachePath = Paths.get("./data/cache")
    if (!Files.exists(cachePath)) {
        System.err.println("Cache directory not found: $cachePath")
        System.exit(1)
    }

    println("=== Cache Integrity Check ===")
    println("Cache path: $cachePath")
    println()

    val jcacheFiles = mutableMapOf<Int, Path>()
    Files.list(cachePath).use { stream ->
        stream.filter { it.fileName.toString().matches(Regex("js5-\\d+\\.jcache")) }
            .forEach { file ->
                val indexId = file.fileName.toString()
                    .removePrefix("js5-")
                    .removeSuffix(".jcache")
                    .toInt()
                jcacheFiles[indexId] = file
            }
    }

    println("Found ${jcacheFiles.size} jcache files: ${jcacheFiles.keys.sorted()}")
    println()

    val indexFiles = jcacheFiles.mapValues { IndexFile(it.value, readOnly = true) }
    val context = DecompressionContext()

    val refTableCrcs = mutableMapOf<Int, Map<Int, Int>>()
    val refTableVersions = mutableMapOf<Int, Map<Int, Int>>()

    println("=== Phase 1: Parsing ref tables for expected CRCs ===")
    for (indexId in indexFiles.keys.sorted().filter { it != 255 }) {
        val refData = indexFiles[indexId]?.getRawTable()
        if (refData == null) {
            println("  Index $indexId: no ref table")
            continue
        }
        try {
            val refTable = parseRefTable(context, refData)
                ?: throw RuntimeException("decompression failed or unknown ref table format")
            refTableCrcs[indexId] = refTable.crcById()
            refTableVersions[indexId] = refTable.versionById()
            println("  Index $indexId: ${refTable.entries.size} archive CRCs parsed from ref table")
        } catch (e: Exception) {
            println("  Index $indexId: FAILED to parse ref table: ${e.message}")
        }
    }
    println()

    var totalContainers = 0
    var totalOk = 0
    var totalTruncated = 0
    var totalOversized = 0
    var totalBadCompression = 0
    var totalDecompFailed = 0
    var totalCrcMismatch = 0
    var totalTooSmall = 0

    val results = mutableListOf<IntegrityResult>()
    val priorityArchives = setOf(59, 28, 62)

    println("=== Phase 2: Validating all containers ===")

    println()
    println("--- Archive Indices (255/N) ---")
    val indexFile255 = indexFiles[255]
    if (indexFile255 != null) {
        for (indexId in indexFiles.keys.sorted().filter { it != 255 }) {
            val data = indexFile255.getRaw(indexId) ?: indexFiles[indexId]?.getRawTable()
            if (data == null) continue
            val result = validateContainer(255, indexId, data, null, context)
            results.add(result)
            totalContainers++
            reportResult("  255/$indexId", result)
            if (result.truncated) totalTruncated++
            if (result.oversized) totalOversized++
            if (result.decompressionOk == false) totalDecompFailed++
            if (result.crcMatch == false) totalCrcMismatch++
            if (result.compression > 3) totalBadCompression++
            if (result.dataSize < 5) totalTooSmall++
            if (!result.truncated && result.decompressionOk != false && result.crcMatch != false) totalOk++
        }
    }

    for (indexId in indexFiles.keys.sorted().filter { it != 255 }) {
        val idxFile = indexFiles[indexId] ?: continue
        val allKeys = idxFile.allKeys()
        if (allKeys.isEmpty()) continue

        val expectedCrcs = refTableCrcs[indexId] ?: emptyMap()
        val sorted = allKeys.sorted()

        println()
        println("--- Index $indexId: ${sorted.size} groups ---")

        var indexOk = 0
        var indexTruncated = 0
        var indexDecompFail = 0
        var indexCrcMismatch = 0

        for (archiveId in sorted) {
            val data = idxFile.getRaw(archiveId) ?: continue
            val expectedCrc = expectedCrcs[archiveId]
            val result = validateContainer(indexId, archiveId, data, expectedCrc, context)
            results.add(result)
            totalContainers++

            if (result.truncated || result.decompressionOk == false || result.crcMatch == false || result.compression > 3 || result.dataSize < 5) {
                reportResult("  $indexId/$archiveId", result)
            }

            if (result.truncated) { totalTruncated++; indexTruncated++ }
            if (result.oversized) totalOversized++
            if (result.decompressionOk == false) { totalDecompFailed++; indexDecompFail++ }
            if (result.crcMatch == false) { totalCrcMismatch++; indexCrcMismatch++ }
            if (result.compression > 3) totalBadCompression++
            if (result.dataSize < 5) totalTooSmall++
            if (!result.truncated && result.decompressionOk != false && result.crcMatch != false) { totalOk++; indexOk++ }
        }

        val statusTag = if (indexTruncated == 0 && indexDecompFail == 0 && indexCrcMismatch == 0) "ALL OK" else "ISSUES"
        println("  [$statusTag] Index $indexId summary: ${sorted.size} groups, $indexOk ok, $indexTruncated truncated, $indexDecompFail decomp-fail, $indexCrcMismatch crc-mismatch")
    }

    println()
    println("=== Phase 3: Priority Archives Detail (59, 28, 62) ===")
    for (indexId in priorityArchives.sorted()) {
        val idxFile = indexFiles[indexId] ?: continue
        val allKeys = idxFile.allKeys()
        val expectedCrcs = refTableCrcs[indexId] ?: emptyMap()
        println()
        println("--- Index $indexId: ${allKeys.size} groups ---")
        for (archiveId in allKeys.sorted().take(20)) {
            val data = idxFile.getRaw(archiveId) ?: continue
            val result = validateContainer(indexId, archiveId, data, expectedCrcs[archiveId], context)
            reportResult("  $indexId/$archiveId", result, verbose = true)
        }
        if (allKeys.size > 20) {
            println("  ... (${allKeys.size - 20} more groups not shown)")
        }
    }

    println()
    println("=== Phase 4: Version Suffix Analysis ===")
    println("Checking if stored containers have trailing version bytes...")
    var containersWithExact2Extra = 0
    var containersWithNoExtra = 0
    var containersWithOtherExtra = 0
    for (result in results) {
        when {
            result.extraBytes == 2 -> containersWithExact2Extra++
            result.extraBytes == 0 -> containersWithNoExtra++
            result.extraBytes > 0 -> containersWithOtherExtra++
        }
    }
    println("  Containers with exactly 0 extra bytes: $containersWithNoExtra")
    println("  Containers with exactly 2 extra bytes (version suffix?): $containersWithExact2Extra")
    println("  Containers with other extra bytes: $containersWithOtherExtra")

    if (containersWithExact2Extra > 0) {
        println()
        println("  WARNING: $containersWithExact2Extra containers have exactly 2 extra bytes beyond the")
        println("  declared compressedSize. This likely indicates a 2-byte version suffix is stored.")
        println("  The CRC comparison above was computed over the FULL stored data. If the ref table")
        println("  CRC is over (data - 2 bytes), the CRC comparison may be wrong for these containers.")

        var crcFixedCount = 0
        for (result in results.filter { it.extraBytes == 2 && it.refTableCrc != null }) {
            val idxFile = indexFiles[result.index] ?: continue
            val data = if (result.index == 255) {
                indexFile255?.getRaw(result.archive) ?: indexFiles[result.archive]?.getRawTable()
            } else {
                idxFile.getRaw(result.archive)
            } ?: continue
            val crcMinus2 = CRC.calculate(data, 0, data.size - 2)
            if (crcMinus2 == result.refTableCrc) {
                crcFixedCount++
                if (crcFixedCount <= 10) {
                    println("  FIX: ${result.index}/${result.archive}: CRC(data-2)=0x${"%08x".format(crcMinus2)} matches refTable CRC")
                }
            }
        }
        if (crcFixedCount > 0) {
            println("  FINDING: $crcFixedCount containers have CRC match when excluding last 2 bytes!")
            println("  This confirms the 2-byte version suffix IS stored and should be excluded from CRC.")
        }
    }

    println()
    println("========================================")
    println("=== FINAL SUMMARY ===")
    println("========================================")
    println("Total containers checked:    $totalContainers")
    println("OK:                          $totalOk")
    println("Truncated:                   $totalTruncated")
    println("Oversized (extra bytes):     $totalOversized")
    println("Bad compression type:        $totalBadCompression")
    println("Decompression failed:        $totalDecompFailed")
    println("CRC mismatch (vs ref table): $totalCrcMismatch")
    println("Too small (< 5 bytes):       $totalTooSmall")
    println("========================================")

    if (totalTruncated + totalDecompFailed + totalCrcMismatch + totalBadCompression + totalTooSmall > 0) {
        println("RESULT: INTEGRITY ISSUES FOUND")
        System.exit(1)
    } else {
        println("RESULT: ALL CONTAINERS VALID")
    }

    for (idxFile in indexFiles.values) {
        idxFile.close()
    }
}

private fun validateContainer(
    index: Int,
    archive: Int,
    data: ByteArray,
    refTableCrc: Int?,
    context: DecompressionContext
): IntegrityResult {
    if (data.size < 5) {
        return IntegrityResult(
            index, archive, data.size,
            -1, 0, 0, 0, 0,
            truncated = true, oversized = false, extraBytes = 0,
            decompressionOk = null, decompressionError = "too small",
            crcMatch = null, storedCrc = null, computedCrc = 0, refTableCrc = refTableCrc
        )
    }

    val headerReader = BufferReader(data)
    val compression = headerReader.readUnsignedByte()
    val compressedSize = headerReader.readInt()

    val headerSize = 5 + if (compression != 0) 4 else 0
    val expectedTotalSize = headerSize + compressedSize
    val actualPayloadSize = data.size - 5
    val expectedPayloadSize = compressedSize + if (compression != 0) 4 else 0

    val truncated = data.size < expectedTotalSize
    val extraBytes = if (data.size > expectedTotalSize) data.size - expectedTotalSize else 0
    val oversized = extraBytes > 0

    val decompressedSize = if (compression != 0 && data.size >= 9) headerReader.readInt() else compressedSize

    var decompressionOk: Boolean? = null
    var decompressionError: String? = null
    if (!truncated && compression in 0..3) {
        try {
            val decompressed = context.decompress(data)
            if (decompressed != null) {
                decompressionOk = true
            } else {
                decompressionOk = false
                decompressionError = "decompress returned null"
            }
        } catch (e: Exception) {
            decompressionOk = false
            decompressionError = "${e::class.simpleName}: ${e.message}"
        }
    } else if (compression > 3) {
        decompressionOk = false
        decompressionError = "invalid compression type $compression"
    }

    val computedCrc = CRC.calculate(data, 0, data.size)
    val computedCrcMinus2 = if (data.size > 2) CRC.calculate(data, 0, data.size - 2) else computedCrc

    val crcMatch = when {
        refTableCrc == null -> null
        computedCrc == refTableCrc -> true
        computedCrcMinus2 == refTableCrc -> true
        else -> false
    }

    return IntegrityResult(
        index, archive, data.size,
        compression, compressedSize, decompressedSize,
        expectedPayloadSize, actualPayloadSize,
        truncated, oversized, extraBytes,
        decompressionOk, decompressionError,
        crcMatch, null, computedCrc, refTableCrc
    )
}

private fun reportResult(prefix: String, result: IntegrityResult, verbose: Boolean = false) {
    val status = buildString {
        append("${result.dataSize}B")
        append(" comp=${result.compression}")
        append(" compSize=${result.compressedSize}")
        if (result.compression != 0) append(" decompSize=${result.decompressedSize}")
        append(" payload=${result.actualPayloadSize}/${result.expectedPayloadSize}")

        if (result.truncated) append(" TRUNCATED(short by ${result.expectedPayloadSize - result.actualPayloadSize})")
        if (result.extraBytes > 0) append(" EXTRA(+${result.extraBytes})")
        if (result.decompressionOk == false) append(" DECOMP_FAIL(${result.decompressionError})")
        if (result.decompressionOk == true && verbose) append(" decomp=OK")
        if (result.crcMatch == false) {
            append(" CRC_MISMATCH(computed=0x${"%08x".format(result.computedCrc)} ref=0x${"%08x".format(result.refTableCrc ?: 0)})")
        }
        if (result.crcMatch == true && verbose) append(" crc=OK")
    }
    println("$prefix: $status")
}
