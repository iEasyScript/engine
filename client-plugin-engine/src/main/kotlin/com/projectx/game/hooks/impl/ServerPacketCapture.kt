package com.projectx.game.hooks.impl

import com.projectx.game.nxt.extent
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.hooks.SupportedOn
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getInt
import com.projectx.game.net.PacketLogger
import com.projectx.game.nxt.OClient
import com.projectx.game.nxt.OConnectionManager
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OServerConnection
import com.projectx.game.platform.Platform
import com.projectx.pathfinder.RebuildRegionMap
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE

/**
 * Captures every incoming server packet (opcode, resolved size, payload) by hooking
 * `TcpConnectionMessage::InitIncoming`, called from `ConnectionManager::TcpIn` after the opcode is
 * ISAAC-deciphered, the size resolved, and the payload read into the owning ServerConnection's
 * receive buffer (BUF_DATA, filled at offset 0), but before handler dispatch.
 *
 * InitIncoming(messagePtr, protEntry, resolvedSize, isaac) does NOT receive the connection — the
 * payload lives in the ServerConnection buffer, and which connection that is can only be known from
 * TcpIn. So we also hook TcpIn to record the exact state it is draining (`*(handle + 8)`), and read
 * the payload from THAT. The previous (opcode, size) guess across the game/login slots stapled a
 * mismatched buffer onto large packets (e.g. logging a stale grid as REBUILD_NORMAL).
 */
object ServerPacketCapture {

    private const val MAX_PAYLOAD = 0x20000

    private val FALLBACK_SLOTS = longArrayOf(
        OConnectionManager.GAME_CONNECTION,
        OConnectionManager.LOGIN_CONNECTION,
    )

    /**
     * The OServerConnection state TcpIn is currently draining. InitIncoming runs synchronously inside
     * the same TcpIn call on the network thread, so a plain volatile is exact here.
     */
    @Volatile
    private var activeConn: MemorySegment? = null

    @JvmStatic
    @Hook("CONNECTIONMANAGER_TCPIN")
    fun onTcpIn(connectionManager: MemorySegment, serverConnectionHandle: MemorySegment) {
        activeConn = runCatching {
            serverConnectionHandle.reinterpret(OConnectionManager.HANDLE_STATE + 8)
                .deref(OConnectionManager.HANDLE_STATE, OServerConnection.extent)
                .takeIf { it.address() != 0L }
        }.getOrNull()
        try {
            HookManager.trampoline(::onTcpIn.name).invoke(connectionManager, serverConnectionHandle)
        } finally {
            activeConn = null
        }
    }

    @JvmStatic
    // MSVC emitted one shared body for Linux's Init/InitIncoming pair and inlined the differing
    // tail, so the Windows offset-table entry points at ReadIncomingMessage: two arguments rather
    // than four, with opcode, size and payload reachable through the ServerConnection instead.
    @SupportedOn(Platform.LINUX)
    @Hook("TCPCONNECTIONMESSAGE_INIT_INCOMING")
    fun onIncomingPacket(
        messagePtr: MemorySegment,
        protEntry: MemorySegment,
        resolvedSize: MemorySegment,
        isaacPtr: MemorySegment
    ) {
        synchronized(Bootstrap.lock) {
            try {
                val opcode = protEntry.reinterpret(8).getInt()
                val size = resolvedSize.address().toInt()
                val conn = activeConn ?: findProcessingConnection(opcode, size)
                when {
                    size <= 0 -> PacketLogger.logServerPacket(opcode, size, ByteArray(0))
                    size > MAX_PAYLOAD -> PacketLogger.logServerPacketDesync(opcode, size, "implausible size")
                    conn == null || !connMatches(conn, opcode, size) ->
                        PacketLogger.logServerPacketDesync(opcode, size, connStateSummary())
                    else -> {
                        val bufData = conn.deref(OServerConnection.BUF_DATA, size.toLong())
                        val payload = ByteArray(size)
                        MemorySegment.copy(bufData, JAVA_BYTE, 0, payload, 0, size)
                        PacketLogger.logServerPacket(opcode, size, payload)
                        RebuildRegionMap.observe(opcode, payload)
                    }
                }
            } catch (e: Throwable) {
                System.err.println("[ServerPacketCapture] Error: ${e.message}")
            }
            HookManager.trampoline(::onIncomingPacket.name)
                .invoke(messagePtr, protEntry, resolvedSize, isaacPtr)
        }
    }

    private fun connMatches(conn: MemorySegment, opcode: Int, size: Int): Boolean =
        runCatching {
            conn.getInt(OServerConnection.CURRENT_OPCODE) == opcode &&
                conn.getInt(OServerConnection.RESOLVED_SIZE) == size
        }.getOrDefault(false)

    private fun findProcessingConnection(opcode: Int, size: Int): MemorySegment? {
        for (off in FALLBACK_SLOTS) {
            val conn = connOrNull(off) ?: continue
            if (connMatches(conn, opcode, size)) return conn
        }
        return null
    }

    private fun connStateSummary(): String {
        val active = activeConn?.let { runCatching { connDesc("active", it) }.getOrDefault("active=<unreadable>") } ?: "active=<null>"
        val slots = FALLBACK_SLOTS.joinToString(" ") { off ->
            val label = if (off == OConnectionManager.GAME_CONNECTION) "game" else "login"
            val conn = connOrNull(off)
            if (conn == null) "$label=<null>" else runCatching { connDesc(label, conn) }.getOrDefault("$label=<unreadable>")
        }
        return "$active $slots"
    }

    private fun connDesc(label: String, conn: MemorySegment): String =
        "$label(op=${conn.getInt(OServerConnection.CURRENT_OPCODE)},sz=${conn.getInt(OServerConnection.RESOLVED_SIZE)})"

    private fun connOrNull(off: Long): MemorySegment? =
        runCatching {
            val conn = Bootstrap.client.ptr.deref(OClient.CONNECTION_MANAGER, OConnectionManager.extent).deref(off, OServerConnection.extent)
            if (conn.address() == 0L) null else conn
        }.getOrNull()
}
