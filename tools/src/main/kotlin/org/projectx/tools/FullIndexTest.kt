package org.projectx.tools

import org.projectx.tools.cachedownloader.JS5Protocol
import org.projectx.tools.util.parseRefTable
import org.projectx.tools.util.toHex
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.compress.DecompressionContext
import world.gregs.voidps.cache.secure.Whirlpool
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.zip.CRC32

private const val HOST = "localhost"
private const val PORT = 43594
private const val MAJOR_VERSION = 948
private const val MINOR_VERSION = 1
private const val JS5_TOKEN = "ev9+VAp5/tMKeNR/7MOuH6lKWS+rGkHK"
private const val ENTRY_SIZE = 80

fun main() {
    val socket = Socket(HOST, PORT)
    socket.soTimeout = 15_000
    val output = DataOutputStream(socket.getOutputStream())
    val input = DataInputStream(socket.getInputStream())
    val decomp = DecompressionContext()
    var passed = 0
    var failed = 0

    fun pass(msg: String) { println("  PASS: $msg"); passed++ }
    fun fail(msg: String) { println("  FAIL: $msg"); failed++ }

    try {
        val sync = JS5Protocol.handshake(output, input, MAJOR_VERSION, MINOR_VERSION, JS5_TOKEN)
        if (sync != 0) { fail("SYNC failed: $sync"); return }
        pass("Handshake SYNC")

        JS5Protocol.sendConnectionInit(output, MAJOR_VERSION)
        pass("ACK + READY")

        JS5Protocol.sendFileRequest(output, 255, 255, major = 0, flags = 0x01)
        val masterRaw = JS5Protocol.readResponse(input).container
        val masterDecomp = decomp.decompress(masterRaw) ?: run { fail("Master index decompress failed"); return }
        val masterReader = BufferReader(masterDecomp)
        val archiveCount = masterReader.readUnsignedByte()
        println("\n=== Master Index: $archiveCount archives ===")
        pass("Master index downloaded (${masterRaw.size} bytes, $archiveCount archives)")

        data class MasterEntry(val crc: Int, val version: Int, val fileCount: Int, val uncompSize: Int, val whirlpool: ByteArray)
        val entries = mutableMapOf<Int, MasterEntry>()
        for (i in 0 until archiveCount) {
            if (1 + (i + 1) * ENTRY_SIZE > masterDecomp.size) break
            val crc = masterReader.readInt()
            val ver = masterReader.readInt()
            val fc = masterReader.readInt()
            val us = masterReader.readInt()
            val wp = ByteArray(64)
            masterReader.readBytes(wp)
            entries[i] = MasterEntry(crc, ver, fc, us, wp)
        }

        val activeArchives = entries.filter { it.value.crc != 0 || it.value.version != 0 }
        println("\n=== Testing ${activeArchives.size} active archives ===\n")

        for ((idx, entry) in activeArchives.toSortedMap()) {
            print("Archive $idx: ")

            JS5Protocol.sendFileRequest(output, 255, idx, major = 0, flags = 0x01)
            val raw: ByteArray
            try {
                raw = JS5Protocol.readResponse(input).container
            } catch (e: Exception) {
                fail("Archive $idx: download failed: ${e.message}")
                continue
            }

            val crc32 = CRC32()
            crc32.update(raw)
            val computedCrc = crc32.value.toInt()
            if (computedCrc != entry.crc) {
                fail("Archive $idx: CRC MISMATCH computed=0x${"%08x".format(computedCrc)} expected=0x${"%08x".format(entry.crc)}")
                continue
            }

            val wp = Whirlpool()
            wp.add(raw)
            val wpOut = ByteArray(64)
            wp.finalize(wpOut)
            val wpMatch = wpOut.contentEquals(entry.whirlpool)

            val decompressed = decomp.decompress(raw)
            if (decompressed == null) {
                fail("Archive $idx: CRC OK, Whirlpool=${if (wpMatch) "OK" else "MISMATCH"}, decompress FAILED")
                continue
            }

            if (decompressed.isEmpty() || decompressed[0].toInt() and 0xFF != 0x07) {
                val fmt = if (decompressed.isEmpty()) "empty" else "0x${"%02x".format(decompressed[0])}"
                fail("Archive $idx: format=$fmt (expected 0x07)")
                continue
            }

            try {
                val result = parseRefTable(decompressed, parseFiles = true)
                if (result == null) {
                    fail("Archive $idx: ref table parse returned null")
                    continue
                }
                println("CRC OK, WP=${if (wpMatch) "OK" else "MISMATCH"}, ver=${result.revision}/${entry.version}, " +
                    "groups=${result.entries.size}, maxGroupId=${result.maxGroupId}, flags=0x${"%02x".format(result.flags)}, " +
                    "totalFiles=${result.totalFileCount}, bytesLeft=${result.bytesRemaining}")
                if (!wpMatch) {
                    fail("Archive $idx: Whirlpool MISMATCH")
                    println("    Expected: ${entry.whirlpool.toHex("")}")
                    println("    Computed: ${wpOut.toHex("")}")
                } else if (result.bytesRemaining != 0) {
                    fail("Archive $idx: ${result.bytesRemaining} bytes remaining after parse")
                } else if (result.entries.isEmpty() && entry.fileCount > 0) {
                    fail("Archive $idx: 0 groups parsed but master says fileCount=${entry.fileCount}")
                } else {
                    pass("Archive $idx")
                }
            } catch (e: Exception) {
                fail("Archive $idx: LoadIndex parse error: ${e.message}")
            }
        }

        println("\n========================================")
        println("=== RESULTS: $passed passed, $failed failed ===")
        println("========================================")
        if (failed > 0) System.exit(1)
    } finally {
        socket.close()
    }
}
