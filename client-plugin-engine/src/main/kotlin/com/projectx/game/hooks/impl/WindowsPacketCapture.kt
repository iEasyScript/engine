package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.hooks.SupportedOn
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getInt
import com.projectx.game.net.PacketLogger
import com.projectx.game.nxt.OPendingMessageNode
import com.projectx.game.nxt.OServerConnection
import com.projectx.game.nxt.OTcpConnectionMessage
import com.projectx.game.platform.Platform
import com.projectx.pathfinder.RebuildRegionMap
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_LONG

private const val MAX_PAYLOAD = 0x20000
private const val CONN_VIEW = 0x300L
private const val MESSAGE_VIEW = 0x58L
private const val NODE_VIEW = 0x20L

/**
 * MSVC folded Linux's `TcpConnectionMessage::Init`/`InitIncoming` pair into one body and inlined the
 * differing tail, so Windows captures incoming traffic at `ReadIncomingMessage` instead. That
 * function is what *populates* the opcode, size and receive buffer, so unlike the Linux hook this one
 * must run the trampoline before reading anything.
 */
object WindowsIncomingPacketCapture {

    @JvmStatic
    @SupportedOn(Platform.WINDOWS)
    @Hook("TCPCONNECTIONMESSAGE_INIT_INCOMING")
    fun onReadIncomingMessage(conn: MemorySegment, outMessage: MemorySegment): MemorySegment {
        val result = HookManager.trampoline(::onReadIncomingMessage.name)
            .invoke(conn, outMessage) as MemorySegment
        synchronized(Bootstrap.lock) {
            try {
                capture(conn, outMessage)
            } catch (e: Throwable) {
                System.err.println("[WindowsIncomingPacketCapture] Error: ${e.message}")
            }
        }
        return result
    }

    private fun capture(conn: MemorySegment, outMessage: MemorySegment) {
        // Every incomplete path — stream not ready, nothing available, fewer bytes than the resolved
        // size, unknown opcode — nulls the object half of the out shared_ptr. Reading the buffer on
        // those would log a torn packet as a real one.
        if (outMessage.reinterpret(16).get(ADDRESS, 8).address() == 0L) return

        val state = conn.reinterpret(CONN_VIEW)
        val opcode = state.getInt(OServerConnection.CURRENT_OPCODE)
        val size = state.getInt(OServerConnection.RESOLVED_SIZE)
        when {
            size <= 0 -> PacketLogger.logServerPacket(opcode, size, ByteArray(0))
            size > MAX_PAYLOAD -> PacketLogger.logServerPacketDesync(opcode, size, "implausible size")
            else -> {
                // The payload is read to the start of the buffer and BUF_POS is zeroed before return,
                // so the resolved size is the only usable length here.
                val payload = ByteArray(size)
                val bufData = state.deref(OServerConnection.BUF_DATA, size.toLong())
                MemorySegment.copy(bufData, JAVA_BYTE, 0, payload, 0, size)
                PacketLogger.logServerPacket(opcode, size, payload)
                RebuildRegionMap.observe(opcode, payload)
            }
        }
    }
}

/**
 * `SendClientMessage` is inlined at all 88 of its Windows call sites, and its stand-in `CreatePacket`
 * returns before the caller writes the payload — a hook there only ever sees the opcode byte. The
 * flush is the one point where a message's final bytes and its prot identity coexist, so the capture
 * happens there, before the trampoline unlinks and recycles the nodes.
 */
object WindowsOutgoingPacketCapture {

    @JvmStatic
    @SupportedOn(Platform.WINDOWS)
    @Hook("SERVERCONNECTION_FLUSHCLIENTMESSAGES")
    fun onFlushClientMessages(conn: MemorySegment): Long {
        synchronized(Bootstrap.lock) {
            try {
                capturePending(conn.reinterpret(CONN_VIEW))
            } catch (e: Throwable) {
                System.err.println("[WindowsOutgoingPacketCapture] Error: ${e.message}")
            }
        }
        return HookManager.trampoline(::onFlushClientMessages.name).invoke(conn) as Long
    }

    private fun capturePending(state: MemorySegment) {
        var remaining = state.get(JAVA_LONG, OServerConnection.PENDING_COUNT)
        if (remaining <= 0L) return
        var node = state.deref(OServerConnection.PENDING_LIST_HEAD, NODE_VIEW)
        while (remaining-- > 0L && node.address() != 0L) {
            val message = node.deref(OPendingMessageNode.MESSAGE, MESSAGE_VIEW)
            if (message.address() == 0L) break
            // PAYLOAD_LENGTH is what gets handed to ClientStream::Write, so it spans the header too.
            val wireLength = message.getInt(OTcpConnectionMessage.PAYLOAD_LENGTH)
            val opcode = message.deref(OTcpConnectionMessage.PROT_PTR, 8L).getInt(0L)
            val headerBytes = PacketLogger.clientMessageHeaderBytes(
                message.getInt(OTcpConnectionMessage.FIXED_SIZE)
            )
            val bodyLength = (wireLength - headerBytes).coerceAtLeast(0)
            if (bodyLength in 1..MAX_PAYLOAD) {
                val payload = ByteArray(bodyLength)
                val bufData = message.deref(OTcpConnectionMessage.BUF_DATA, wireLength.toLong())
                MemorySegment.copy(bufData, JAVA_BYTE, headerBytes.toLong(), payload, 0, bodyLength)
                PacketLogger.logClientPacket(opcode, bodyLength, payload)
            } else {
                PacketLogger.logClientPacket(opcode, bodyLength, ByteArray(0))
            }
            node = node.deref(0L, NODE_VIEW)
        }
    }
}
