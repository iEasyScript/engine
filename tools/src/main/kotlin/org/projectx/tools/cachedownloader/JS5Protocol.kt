package org.projectx.tools.cachedownloader

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * A fully reassembled JS5 response container.
 *
 * @param container compression(1) + compressedSize(4 BE) + [decompressedSize(4) if compressed] + data
 * @param wire      the exact raw wire bytes (response header + payload + continuation headers),
 *                  only populated when requested via [JS5Protocol.readResponse] `captureWire`.
 */
data class JS5Response(val index: Int, val archive: Int, val container: ByteArray, val wire: ByteArray? = null)

/**
 * Canonical NXT JS5 TCP protocol implementation: handshake, connection init, file
 * requests and block-framed response reading. Every JS5 tool (downloader, integration
 * tests, wire-compare, live capture) must go through this object — do not reimplement
 * the framing math elsewhere.
 */
object JS5Protocol {
    /** NXT JS5 block size: a 5-byte continuation header is inserted every [BLOCK_SIZE] wire bytes. */
    const val BLOCK_SIZE = 102400

    /** First-segment header: index(1) + hash(4) + compression(1) + compressedSize(4). */
    const val RESPONSE_HEADER_LEN = 10

    /** Continuation header: index(1) + hash(4). */
    const val CONTINUATION_HEADER_LEN = 5

    /** Sanity bound for the compressedSize field — anything larger is a desync. */
    const val MAX_COMPRESSED_SIZE = 50_000_000

    /**
     * Opens a connected socket, performs handshake + connection init.
     * Returns the socket, input stream, and output stream.
     */
    fun connect(
        host: String, port: Int,
        major: Int, minor: Int, token: String,
        soTimeout: Int = 30_000
    ): Triple<Socket, DataInputStream, DataOutputStream> {
        val sock = Socket(host, port)
        sock.soTimeout = soTimeout
        val out = DataOutputStream(sock.getOutputStream())
        val inp = DataInputStream(sock.getInputStream())

        val response = handshake(out, inp, major, minor, token)
        if (response != 0) {
            sock.close()
            throw IllegalStateException("Handshake failed: $response")
        }

        sendConnectionInit(out, major)
        return Triple(sock, inp, out)
    }

    /**
     * Sends JS5 handshake and returns the server response byte (0 = SYNC).
     */
    fun handshake(
        out: DataOutputStream, inp: DataInputStream,
        major: Int, minor: Int, token: String
    ): Int {
        val tokenBytes = token.toByteArray(Charsets.US_ASCII)
        out.writeByte(15)
        out.writeByte(tokenBytes.size + 10)
        out.writeInt(major)
        out.writeInt(minor)
        out.write(tokenBytes)
        out.writeByte(0)
        out.writeByte(0)
        out.flush()
        return inp.readUnsignedByte()
    }

    /**
     * Sends ConnectionInitialized(6) + LoggedOut(3) after successful handshake.
     */
    fun sendConnectionInit(out: DataOutputStream, major: Int) {
        out.writeByte(6)
        out.writeByte(0); out.writeByte(0); out.writeByte(5)
        out.writeShort(0); out.writeShort(major); out.writeShort(0)
        out.writeByte(3)
        out.writeByte(0); out.writeByte(0); out.writeByte(5)
        out.writeShort(0); out.writeShort(major); out.writeShort(0)
        out.flush()
    }

    /**
     * Sends a 10-byte file request: [flags][index][archive:4 BE][major:2][0:2].
     * Default flags 33 (0x21) = high-priority request as sent by the cache downloader;
     * local-test tools use 0x01 with major=0 (trailing 4 zero bytes).
     */
    fun sendFileRequest(out: DataOutputStream, index: Int, archive: Int, major: Int, flags: Int = 33) {
        out.writeByte(flags)
        out.writeByte(index)
        out.writeInt(archive)
        out.writeShort(major)
        out.writeShort(0)
        out.flush()
    }

    /**
     * Incremental reassembler for one (index, archive) response. The caller reads each
     * segment's 5-byte id header itself (index + hash) — this matters for multiplexed
     * streams where segments of different responses interleave — then feeds the stream
     * to [readSegment] until it returns true.
     */
    class ResponseAssembler(val index: Int, val archive: Int) {
        /** Container bytes; null until the first segment's compression header was read. */
        var container: ByteArray? = null
            private set

        /** Compression type byte; valid after the first [readSegment]. */
        var compression: Int = -1
            private set

        /** compressedSize field; valid after the first [readSegment]. */
        var compressedSize: Int = -1
            private set

        /** Payload bytes received so far (excluding the 5-byte container header). */
        var payloadWritten: Int = 0
            private set

        private var totalSize = 0
        private var written = 0

        /**
         * Consume one block segment (the part of a response between two block boundaries).
         * The first call also reads the 5-byte compression header. Returns true when the
         * container is complete.
         */
        fun readSegment(inp: DataInputStream, onChunk: ((data: ByteArray, offset: Int, length: Int) -> Unit)? = null): Boolean {
            var blockOffset = CONTINUATION_HEADER_LEN
            if (container == null) {
                compression = inp.readUnsignedByte()
                compressedSize = inp.readInt()
                if (compressedSize < 0 || compressedSize > MAX_COMPRESSED_SIZE) {
                    throw IllegalStateException("Bad compressedSize=$compressedSize for index=$index archive=$archive")
                }
                blockOffset = RESPONSE_HEADER_LEN
                val totalDataLen = compressedSize + (if (compression != 0) 4 else 0)
                totalSize = 5 + totalDataLen
                val c = ByteArray(totalSize)
                c[0] = compression.toByte()
                c[1] = (compressedSize shr 24).toByte()
                c[2] = (compressedSize shr 16).toByte()
                c[3] = (compressedSize shr 8).toByte()
                c[4] = compressedSize.toByte()
                container = c
                written = 5
            }
            val c = container!!
            val toRead = (totalSize - written).coerceAtMost(BLOCK_SIZE - blockOffset)
            if (toRead > 0) {
                inp.readFully(c, written, toRead)
                onChunk?.invoke(c, written, toRead)
                written += toRead
                payloadWritten += toRead
            }
            return written == totalSize
        }
    }

    /**
     * Reads one complete JS5 response whose segments arrive contiguously (single
     * outstanding request), stripping block framing.
     *
     * @param captureWire    also reconstruct the exact raw wire bytes into [JS5Response.wire]
     * @param onHeader       called with (index, hash, compression, compressedSize) after the response header
     * @param onContinuation called with (payloadOffset, index, hash) for every continuation header
     */
    fun readResponse(
        inp: DataInputStream,
        captureWire: Boolean = false,
        onHeader: ((index: Int, hash: Int, compression: Int, compressedSize: Int) -> Unit)? = null,
        onContinuation: ((payloadOffset: Int, index: Int, hash: Int) -> Unit)? = null,
    ): JS5Response {
        val idx = inp.readUnsignedByte()
        val hash = inp.readInt()
        val archive = hash and 0x7FFFFFFF

        val wire = if (captureWire) ByteArrayOutputStream() else null
        fun writeIdHeader(i: Int, h: Int) {
            wire ?: return
            wire.write(i)
            wire.write((h shr 24) and 0xFF); wire.write((h shr 16) and 0xFF)
            wire.write((h shr 8) and 0xFF); wire.write(h and 0xFF)
        }

        val assembler = ResponseAssembler(idx, archive)
        var first = true
        while (true) {
            if (!first) {
                val contIndex = inp.readUnsignedByte()
                val contHash = inp.readInt()
                onContinuation?.invoke(assembler.payloadWritten, contIndex, contHash)
                writeIdHeader(contIndex, contHash)
            }
            val headerPending = first
            val complete = assembler.readSegment(inp) { data, offset, length ->
                if (headerPending) {
                    // Emit header callbacks/bytes before the first payload chunk.
                    onHeader?.invoke(idx, hash, assembler.compression, assembler.compressedSize)
                    if (wire != null) {
                        writeIdHeader(idx, hash)
                        wire.write(assembler.compression)
                        val cs = assembler.compressedSize
                        wire.write((cs shr 24) and 0xFF); wire.write((cs shr 16) and 0xFF)
                        wire.write((cs shr 8) and 0xFF); wire.write(cs and 0xFF)
                    }
                }
                wire?.write(data, offset, length)
            }
            if (first && assembler.payloadWritten == 0) {
                // Empty payload: the chunk callback never fired, emit the header now.
                onHeader?.invoke(idx, hash, assembler.compression, assembler.compressedSize)
                if (wire != null) {
                    writeIdHeader(idx, hash)
                    wire.write(assembler.compression)
                    val cs = assembler.compressedSize
                    wire.write((cs shr 24) and 0xFF); wire.write((cs shr 16) and 0xFF)
                    wire.write((cs shr 8) and 0xFF); wire.write(cs and 0xFF)
                }
            }
            first = false
            if (complete) break
        }

        return JS5Response(idx, archive, assembler.container!!, wire?.toByteArray())
    }
}
