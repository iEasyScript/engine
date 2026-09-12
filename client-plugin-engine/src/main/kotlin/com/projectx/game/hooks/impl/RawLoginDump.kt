package com.projectx.game.hooks.impl

import com.projectx.game.nxt.extent
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getInt
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.net.PacketLogger
import com.projectx.game.nxt.OClient
import com.projectx.game.nxt.OConnectionManager
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OServerConnection
import com.projectx.ui.UIState
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE

/**
 * Dumps the raw on-the-wire login/handshake bytes (the RSA block + server preamble) that the
 * decoded prot hooks never see — during login the bytes are read by the LoginManager state machine,
 * not dispatched through TcpIn as ServerProts. Post-login the decoded hooks
 * ([ServerPacketCapture] / [SendClientMessage]) take over, so dumping stops at LOGGED_IN.
 *
 * Toggled live from the UI (Settings → "Raw login/RSA dump", [UIState.rawLoginDumpEnabled]). Off by
 * default — these are the per-byte socket funnels, so when off the hooks are pure passthroughs (one
 * boolean check) and add no overhead to normal networking.
 */
object RawLoginDump {
    private const val LOGGED_IN_STATE = 30

    private val LOGIN_SLOTS = longArrayOf(
        OConnectionManager.LOGIN_CONNECTION,  // lobby login
        OConnectionManager.GAME_CONNECTION,   // world login (reconnect runs on the game slot — see StartWorldLogin)
    )

    /**
     * True while [stream] is a login/handshake stream still mid-login — false when disabled, already
     * LOGGED_IN, or not a login stream. Both the lobby (login slot) and world (game slot) logins are
     * covered. Every pointer is null-checked the way the binary reads it (never via /proc/self/maps),
     * so a partly-initialised ConnectionManager or a JS5/other stream is simply ignored.
     */
    private fun isLoginStream(streamAddr: Long): Boolean {
        if (streamAddr == 0L || !UIState.rawLoginDumpEnabled.value) return false
        val client = Bootstrap.client.ptr.getOrNull ?: return false
        if (client.getInt(OClient.MAIN_STATE) == LOGGED_IN_STATE) return false
        val connMgr = client.deref(OClient.CONNECTION_MANAGER, OConnectionManager.extent).getOrNull ?: return false
        return LOGIN_SLOTS.any { slot ->
            val conn = connMgr.deref(slot, OServerConnection.extent).getOrNull ?: return@any false
            conn.deref(OServerConnection.CLIENT_STREAM, 0x10L).getOrNull?.address() == streamAddr
        }
    }

    private fun copyOf(buf: MemorySegment, len: Int): ByteArray {
        val src = buf.reinterpret(len.toLong())
        return ByteArray(len).also { MemorySegment.copy(src, JAVA_BYTE, 0L, it, 0, len) }
    }

    @JvmStatic
    @Hook("CLIENTSTREAM_READ")
    fun rawLoginRead(stream: MemorySegment, buf: MemorySegment, len: Long): Long {
        val read = HookManager.trampoline(::rawLoginRead.name).invokeExact(stream, buf, len) as Long
        if (read > 0L && isLoginStream(stream.address())) {
            runCatching { PacketLogger.logRaw('S', copyOf(buf, read.toInt())) }
        }
        return read
    }

    @JvmStatic
    @Hook("CLIENTSTREAM_WRITE")
    fun rawLoginWrite(stream: MemorySegment, buf: MemorySegment, len: Long): Long {
        if (len > 0L && isLoginStream(stream.address())) {
            runCatching { PacketLogger.logRaw('C', copyOf(buf, len.toInt())) }
        }
        return HookManager.trampoline(::rawLoginWrite.name).invokeExact(stream, buf, len) as Long
    }
}
