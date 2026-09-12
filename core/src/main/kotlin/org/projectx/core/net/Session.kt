package org.projectx.core.net

import io.ktor.utils.io.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import org.projectx.core.EnvVars
import org.projectx.core.Logger.logError
import org.projectx.core.Logger.logTrace
import org.projectx.core.Logger.logWarn
import org.projectx.core.net.prot.*
import world.gregs.voidps.buffer.readUByte
import world.gregs.voidps.buffer.readUShort
import world.gregs.voidps.buffer.writeByte
import world.gregs.voidps.buffer.writeSmart
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass

open class Session(
    private val write: ByteWriteChannel,
    val isaacIn: Isaac,
    private val isaacOut: Isaac?,
    val ip: String,
    val codec: Codec,
) {
    enum class State { CONNECTED, LOST_CONNECTION, DISCONNECTED }

    val readChannel = Channel<ClientProt>(capacity = EnvVars.packetQueueCapacity)
    private val pendingPackets = ConcurrentLinkedQueue<ServerProt>()

    /**
     * Serialises all writers (session loop, world tick via [flushBlocking], gateway
     * coroutines via [send]/[flush]) so every opcode+length+payload sequence — and the
     * isaacOut.nextInt() calls it entails — hits the channel contiguously. Interleaved
     * writes would permanently desync the client's ISAAC stream.
     */
    private val writeMutex = Mutex()

    @Volatile
    var disconnected: Boolean = false
    private var disconnect: (() -> Unit)? = null
    private var disconnecting: (() -> Unit)? = null

    @Volatile
    private var state: State = State.CONNECTED

    fun onDisconnected(block: () -> Unit) {
        disconnect = block
    }

    fun onDisconnecting(block: () -> Unit) {
        disconnecting = block
    }

    suspend fun disconnect(reason: Int) {
        if (disconnected) return
        writeMutex.withLock {
            write.writeByte(reason)
        }
        disconnect()
    }

    suspend fun disconnect() {
        if (disconnected) return
        disconnected = true
        write.flushAndClose()
        state = State.DISCONNECTED
        disconnect?.invoke()
    }

    fun exit() {
        if (state == State.CONNECTED) {
            state = State.LOST_CONNECTION
            disconnecting?.invoke()
        }
    }

    /**
     * Queues a packet to be sent on the next [flush]. Safe to call from non-suspend contexts.
     */
    fun queuePacket(packet: ServerProt) {
        if (disconnected) return
        pendingPackets.add(packet)
    }

    fun pendingCount(): Int = pendingPackets.size

    open suspend fun flush() {
        if (disconnected) return
        try {
            writeMutex.withLock {
                var packet = pendingPackets.poll()
                while (packet != null) {
                    encodePacket(packet, noIsaac = false)
                    packet = pendingPackets.poll()
                }
                write.flush()
            }
        } catch (e: Exception) {
            logWarn("Client error:", e)
            disconnect()
        }
    }

    /**
     * Blocking wrapper for [flush], safe to call from Java / non-suspend contexts
     * such as the world thread's tick loop.
     */
    fun flushBlocking() {
        if (disconnected) return
        runBlocking { flush() }
    }

    /**
     * Non-suspend poll for the next decoded packet.
     * Returns null if channel is empty. Safe to call from Java.
     */
    fun pollPacket(): ClientProt? = readChannel.tryReceive().getOrNull()

    suspend fun readPackets(input: ByteReadChannel) {
        try {
            while (!disconnected) {
                // C2S opcodes are always a single ISAAC-ciphered byte (ClientProt is 0..255); the
                // 2-byte >=128 opcode form is server->client ONLY. A 2-byte read here desyncs on any
                // client op>=128.
                val opcode = (input.readUByte() - isaacIn.nextInt()) and 0xff
                val clientProt = codec.clientProtsByOpcode[opcode]
                if (clientProt == null) {
                    // No decoder registered for this opcode. Frame it from the stub
                    // size metadata so the stream stays in sync, deliver it as an
                    // UnhandledClientProt, and continue. Only an opcode with no size
                    // metadata at all is fatal.
                    val info = codec.clientProtInfo[opcode]
                    if (info == null) {
                        logError("Missing ClientProt with opcode $opcode")
                        return
                    }
                    val skipSize = when (info.size) {
                        is ProtSize.Fixed -> info.size.length
                        ProtSize.VarByte -> input.readUByte()
                        ProtSize.VarShort -> input.readUShort()
                    }
                    input.readPacket(skipSize)
                    readChannel.send(UnhandledClientProt(opcode, info.name, skipSize))
                    continue
                }
                val size = when (clientProt.size) {
                    is ProtSize.Fixed -> clientProt.size.length
                    ProtSize.VarByte -> input.readUByte()
                    ProtSize.VarShort -> input.readUShort()
                }
                val packet = input.readPacket(size)

                val packetData = try {
                    clientProt.decoder?.invoke(packet, size)
                        ?: if (clientProt.protClass == UnhandledClientProt::class) {
                            UnhandledClientProt(opcode, codec.clientProtName(opcode), size)
                        } else {
                            codec.createInstanceForOpcode<ClientProt>(opcode)
                        }
                } catch (e: Exception) {
                    logError("Decoder exception for opcode $opcode (${clientProt.protClass.simpleName}, size=$size): ${e::class.simpleName}: ${e.message}")
                    continue
                }
                if (packetData == null) {
                    logError("Failed to create packet instance for opcode $opcode")
                    continue
                }
                readChannel.send(packetData)
            }
        } catch (e: Exception) {
            if (isExpectedDisconnect(e)) {
                logTrace("Session read ended: ${e::class.simpleName}: ${e.message}")
            } else {
                throw e
            }
        } finally {
            if (!disconnected) {
                disconnected = true
                state = State.LOST_CONNECTION
                disconnecting?.invoke()
            }
        }
    }

    /** Write a raw ServerProt packet with pre-built payload (VarShort framing). */
    suspend fun writeRawServerProt(opcode: Int, payload: ByteArray) {
        if (disconnected) return
        writeMutex.withLock {
            writeOpcode(opcode, isaacOut)
            write.writeShort(payload.size.toShort())
            write.writeFully(payload)
        }
    }

    /** True if the active codec has an encoder registered for [type]; logs once per type when not. */
    fun supportsServerProt(type: KClass<out ServerProt>): Boolean = codec.supportsServerProt(type)

    /**
     * Sends [serverProt] only when the active codec has an encoder for it; returns true when sent.
     * Use at call sites that emit packets with a known capability gap (no RE'd opcode in the
     * active revision yet) — the gap is reported once at INFO by [Codec.supportsServerProt]
     * instead of producing per-send warn spam.
     */
    suspend fun sendIfSupported(serverProt: ServerProt): Boolean {
        if (!supportsServerProt(serverProt::class)) return false
        send(serverProt)
        return true
    }

    open suspend fun send(serverProt: ServerProt, noIsaac: Boolean = false) {
        if (disconnected) return
        try {
            writeMutex.withLock {
                encodePacket(serverProt, noIsaac)
            }
        } catch (e: Exception) {
            logWarn("Client error:", e)
            disconnect()
        }
    }

    /**
     * Encodes and writes one packet (opcode + optional length + payload).
     * MUST be called with [writeMutex] held so the ISAAC opcode stream stays contiguous.
     */
    private suspend fun encodePacket(serverProt: ServerProt, noIsaac: Boolean) {
        val encoder = codec.serverProts[serverProt::class]
        if (encoder == null) {
            logWarn("Missing ServerProt encoder: ${serverProt::class}")
            return
        }
        if (encoder.opcode == Codec.ZONE_ONLY_OPCODE) {
            logWarn("${serverProt::class.simpleName} only exists inside a zone bundle — standalone send dropped")
            return
        }

        when (encoder.size) {
            is ProtSize.Fixed -> {
                if (EnvVars.packetValidateSizes && encoder.encoder != null) {
                    val payload = encodeToBuffer(serverProt, encoder)
                    val packetLength = payload.size.toInt()
                    val expectedLength = encoder.size.length
                    if (packetLength != expectedLength) {
                        logWarn("Fixed packet size mismatch for ${serverProt::class.simpleName}: expected=$expectedLength actual=$packetLength opcode=${encoder.opcode}")
                    }
                    writeOpcode(encoder.opcode, if (noIsaac) null else isaacOut)
                    write.writePacket(payload)
                } else {
                    writeOpcode(encoder.opcode, if (noIsaac) null else isaacOut)
                    encoder.encoder?.invoke(serverProt, write)
                }
            }
            ProtSize.VarByte, ProtSize.VarShort -> {
                val payload = encodeToBuffer(serverProt, encoder)
                val packetLength = payload.size.toInt()

                if (encoder.size == ProtSize.VarByte && packetLength > 255)
                    logWarn("Packet length exceeds VarByte maximum ($packetLength > 255)")
                else if (encoder.size == ProtSize.VarShort && packetLength > 65535)
                    logWarn("Packet length exceeds VarShort maximum ($packetLength > 65535)")

                writeOpcode(encoder.opcode, if (noIsaac) null else isaacOut)

                if (encoder.size == ProtSize.VarByte)
                    write.writeByte(packetLength.toByte())
                else
                    write.writeShort(packetLength.toShort())

                write.writePacket(payload)
            }
        }
    }

    /**
     * Encodes the payload into a growable in-memory [Buffer]. The [asByteWriteChannel] wrapper
     * gives encoders their normal [ByteWriteChannel] receiver without any coroutine channel —
     * writes land directly in the buffer and never suspend.
     */
    private suspend fun encodeToBuffer(serverProt: ServerProt, encoder: Codec.ServerProtCodec): Buffer {
        val payload = Buffer()
        val encode = encoder.encoder ?: return payload
        val channel = payload.asByteWriteChannel()
        encode.invoke(serverProt, channel)
        channel.flush()
        return payload
    }

    private suspend fun writeOpcode(opcode: Int, cipher: Isaac?) {
        if (opcode < 0) return
        if (cipher != null) {
            if (opcode >= 128) {
                write.writeByte(((opcode shr 8) + 128) + cipher.nextInt())
                write.writeByte(opcode + cipher.nextInt())
            } else
                write.writeByte(opcode + cipher.nextInt())
        } else
            write.writeSmart(opcode)
    }

    companion object {
        /**
         * Returns true if the exception represents an expected client disconnect
         * (connection reset, EOF, closed channel) rather than a real error.
         */
        fun isExpectedDisconnect(e: Throwable): Boolean {
            return e is ClosedByteChannelException
                || e is EOFException
                || e is SocketException
                || e is ClosedChannelException
                || (e is IOException && e.message?.let {
                    it.contains("reset", ignoreCase = true)
                    || it.contains("broken pipe", ignoreCase = true)
                    || it.contains("closed", ignoreCase = true)
                } == true)
                || (e.cause != null && isExpectedDisconnect(e.cause!!))
        }
    }
}
