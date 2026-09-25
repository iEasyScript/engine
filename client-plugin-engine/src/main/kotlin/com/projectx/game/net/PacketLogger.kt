package com.projectx.game.net

import com.projectx.game.net.packetlog.PacketRecorder
import com.projectx.script.api.ServerTick
import com.projectx.util.Configuration
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.write
import org.projectx.core.net.prot.Codec
import org.projectx.core.net.prot.ProtRevisions
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Packet dump pipeline. Opcode names, sizes and decoders all come from the shared `:core` protocol -
 * the engine carries no opcode tables of its own, and no revision number either: which one this
 * build speaks is [ProtRevisions]' business, so a new revision needs no change here.
 *
 * Both directions fall back to raw hex where a prot has no decoder yet. The login/RSA handshake is
 * dumped byte-for-byte via [logRaw].
 */
object PacketLogger {
    private val codec: Codec = ProtRevisions.currentCodec()
    private var writer: BufferedWriter? = null
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val fileFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /** Raw server-packet observers for scripts; invoked on the network hook thread, keep them fast. */
    val serverPacketListeners = CopyOnWriteArrayList<(opcode: Int, payload: ByteArray) -> Unit>()

    /**
     * The text dump is opt-in and off by default: it costs roughly ten bytes on disk per payload
     * byte and grew unbounded, which is what the packet database replaces. Enable it only to read a
     * capture by eye.
     */
    fun init() {
        if (!Configuration.config.packetLogTextDumpEnabled) return
        val logDir = File(System.getProperty("user.home"), ".projectx/logs")
        logDir.mkdirs()
        val logFile = File(logDir, "packets-${LocalDateTime.now().format(fileFmt)}.log")
        writer = logFile.bufferedWriter(Charsets.UTF_8, bufferSize = 8192).also {
            it.write("# Packet log started at ${LocalTime.now().format(timeFmt)}\n")
            it.flush()
        }
        println("[PacketLogger] Logging to ${logFile.absolutePath}")
    }

    fun close() {
        writer?.let {
            runCatching {
                it.write("# Packet log closed at ${LocalTime.now().format(timeFmt)}\n")
                it.flush()
                it.close()
            }
        }
        writer = null
    }

    private val serverTickEndOpcode: Int? by lazy {
        codec.serverProtInfo.entries.firstOrNull { it.value.name == "SERVER_TICK_END" }?.key
    }

    fun logServerPacket(opcode: Int, size: Int, payload: ByteArray) {
        if (opcode == serverTickEndOpcode) ServerTick.onTickEnd()
        for (listener in serverPacketListeners) runCatching { listener(opcode, payload) }
        PacketRecorder.recordServer(opcode, payload)
        emit('S', codec.serverProtName(opcode), opcode, size, payload, decoded = decodeServer(opcode, payload))
    }

    /**
     * Incoming packet whose payload could not be read coherently (no connection's in-flight
     * (opcode, size) matched the hook). The body is withheld rather than fabricated - a withheld,
     * clearly-marked entry is honest; a mislabeled body silently corrupts every downstream analysis.
     */
    fun logServerPacketDesync(opcode: Int, size: Int, detail: String) {
        PacketRecorder.recordServerDesync(opcode)
        val name = runCatching { codec.serverProtName(opcode) }.getOrDefault("UNKNOWN")
        val time = LocalTime.now().format(timeFmt)
        writeLine("[$time] S> [DESYNC] $name (op=$opcode, claimed ${size}B) payload withheld - $detail")
    }

    /**
     * Wire bytes preceding the body of an outgoing message. Opcode width is asymmetric: outgoing
     * opcodes are always a single byte, and the two-byte `>= 128` form is incoming-only. Both
     * platforms capture the body only, so they must agree on this or their logs are not comparable.
     */
    fun clientMessageHeaderBytes(fixedSize: Int): Int =
        1 + when (fixedSize) {
            -1 -> 1
            -2 -> 2
            else -> 0
        }

    fun logClientPacket(opcode: Int, size: Int, payload: ByteArray) {
        PacketRecorder.recordClient(opcode, payload)
        emit('C', codec.clientProtName(opcode), opcode, size, payload, decoded = decodeClient(opcode, payload))
    }

    /** Raw on-the-wire bytes (login/RSA handshake, before prots/ISAAC carry meaning). */
    fun logRaw(direction: Char, bytes: ByteArray) =
        emit(direction, "RAW", opcode = -1, size = bytes.size, payload = bytes, decoded = null)

    private fun decodeClient(opcode: Int, payload: ByteArray): String? {
        val decoder = codec.clientProtsByOpcode[opcode]?.decoder ?: return null
        return runCatching {
            val source = Buffer().apply { write(payload) }
            runBlocking { decoder.invoke(source, payload.size) }.toString()
        }.getOrNull()
    }

    private fun decodeServer(opcode: Int, payload: ByteArray): String? {
        val decoder = codec.serverDecodersByOpcode[opcode] ?: return null
        return runCatching {
            val source = Buffer().apply { write(payload) }
            runBlocking { decoder.invoke(source, payload.size) }
        }.getOrNull()
    }

    private fun emit(direction: Char, name: String, opcode: Int, size: Int, payload: ByteArray, decoded: String?) {
        val time = LocalTime.now().format(timeFmt)
        val hex = hexDump(payload)

        val sb = StringBuilder(96)
        sb.append('[').append(time).append("] ").append(direction).append("> ").append(name)
        if (opcode >= 0) sb.append(" (op=").append(opcode).append(", ").append(size).append("B)")
        else sb.append(" (").append(size).append("B)")
        if (decoded != null) sb.append("\n    ").append(decoded)
        if (payload.isNotEmpty()) sb.append("\n    hex: ").append(hex)
        writeLine(sb.toString())
    }

    @Synchronized
    private fun writeLine(line: String) {
        runCatching {
            writer?.let {
                it.write(line)
                it.newLine()
                it.flush()
            }
        }
    }

    private fun hexDump(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
}
