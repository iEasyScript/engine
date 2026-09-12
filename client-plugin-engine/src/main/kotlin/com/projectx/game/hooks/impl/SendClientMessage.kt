package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.hooks.SupportedOn
import com.projectx.game.net.PacketLogger
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OTcpConnectionMessage
import com.projectx.game.platform.Platform
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * Hooks ServerConnection::SendClientMessage to capture outgoing client packets when the game enqueues
 * a fully-written message for transmission.
 */
object SendClientMessage {
    @JvmStatic
    // MSVC inlines this into every call site, leaving Windows with nothing to hook; there the
    // outgoing side is captured from the flush path instead — see WindowsPacketCapture.
    @SupportedOn(Platform.LINUX)
    @Hook("SENDCLIENTMESSAGE")
    fun sendClientMessageHook(serverConnPtr: MemorySegment, messageSharedPtr: MemorySegment) {
        synchronized(Bootstrap.lock) {
            try {
                val sharedPtr = messageSharedPtr.reinterpret(16)
                val objectPtr = sharedPtr.get(ADDRESS, 8).reinterpret(0x58)

                val opcode = objectPtr.get(JAVA_INT, 0)
                val fixedSize = objectPtr.get(JAVA_INT, OTcpConnectionMessage.FIXED_SIZE)
                val bufData = objectPtr.get(ADDRESS, OTcpConnectionMessage.BUF_DATA)
                val bufWritePos = objectPtr.get(JAVA_LONG, OTcpConnectionMessage.BUF_WRITE_POS).toInt()

                val headerBytes = PacketLogger.clientMessageHeaderBytes(fixedSize)
                val payloadSize = maxOf(0, bufWritePos - headerBytes)

                val payload = if (payloadSize > 0 && bufData.address() != 0L) {
                    val buf = bufData.reinterpret(bufWritePos.toLong())
                    ByteArray(payloadSize).also { arr ->
                        MemorySegment.copy(buf, JAVA_BYTE, headerBytes.toLong(), arr, 0, payloadSize)
                    }
                } else {
                    ByteArray(0)
                }

                PacketLogger.logClientPacket(opcode, payloadSize, payload)
            } catch (_: Throwable) {
                // Fail open: send the packet if anything goes wrong
            }
            HookManager.trampoline(::sendClientMessageHook.name)
                .invoke(serverConnPtr, messageSharedPtr)
        }
    }
}
