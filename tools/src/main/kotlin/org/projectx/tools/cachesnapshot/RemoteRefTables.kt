package org.projectx.tools.cachesnapshot

import org.projectx.tools.cachedownloader.JS5Protocol
import org.projectx.tools.util.RefTable
import org.projectx.tools.util.parseRefTable
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.compress.DecompressionContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Fetches reference tables straight from a JS5 host, so a snapshot can be taken of a cache we have
 * not downloaded. Reference tables are small, so this describes an entire remote cache at archive
 * granularity for a few hundred kilobytes of traffic — which is what makes a download dry run
 * possible without fetching any archive data.
 */
class RemoteRefTables(
    private val host: String,
    private val port: Int,
    private val major: Int,
    private val minor: Int,
    private val token: String,
) : AutoCloseable {

    data class IndexMeta(val index: Int, val crc: Int, val version: Int, val files: Int, val size: Int) {
        val populated: Boolean get() = crc != 0 || version != 0
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var requests = 0

    private fun connect() {
        close()
        val (sock, inp, out) = JS5Protocol.connect(host, port, major, minor, token)
        socket = sock
        input = inp
        output = out
        requests = 0
    }

    private fun ensure() {
        if (socket == null || requests >= MAX_REQUESTS_PER_CONNECTION) connect()
    }

    private fun request(index: Int, archive: Int): ByteArray {
        ensure()
        requests++
        JS5Protocol.sendFileRequest(output!!, index, archive, major)
        return JS5Protocol.readResponse(input!!).container
    }

    fun master(context: DecompressionContext): List<IndexMeta> {
        val raw = request(MASTER, MASTER)
        val decompressed = context.decompress(raw) ?: throw IllegalStateException("Master index did not decompress")
        val reader = BufferReader(decompressed)
        val count = reader.readUnsignedByte()
        return List(count) { i ->
            val crc = reader.readInt()
            val version = reader.readInt()
            val files = reader.readInt()
            val size = reader.readInt()
            reader.skip(WHIRLPOOL_BYTES)
            IndexMeta(i, crc, version, files, size)
        }
    }

    fun refTable(index: Int, context: DecompressionContext): RefTable? =
        parseRefTable(context, request(MASTER, index), parseFiles = true)

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        const val MASTER = 255
        private const val MAX_REQUESTS_PER_CONNECTION = 25
        private const val WHIRLPOOL_BYTES = 64
    }
}
