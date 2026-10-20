package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.HookManager
import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.net.ClientProtocol
import com.projectx.game.net.PacketLogger
import com.projectx.game.nxt.OGlobal
import com.projectx.game.nxt.OServerConnection
import com.projectx.game.nxt.OServerProtDescriptor
import com.projectx.game.nxt.OffsetTable
import com.projectx.game.nxt.extent
import com.projectx.pathfinder.RebuildRegionMap
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures incoming packets where TcpIn hands each one to its prot's handler, for clients that inline
 * `TcpConnectionMessage::InitIncoming` and so leave no function to hook. Every server prot descriptor holds
 * its handler as a `std::function`, and each one's invoker slot is pointed here.
 */
object ServerProtDispatchCapture {

    private const val MAX_PAYLOAD = 0x20000
    private const val KEY_PREFIX = "SERVERPROT_HANDLER_"

    /** The handler's status when it could not finish yet; TcpIn keeps the packet and hands it over again. */
    private const val DEFERRED = 1

    private val keys = ConcurrentHashMap<Long, String>()

    fun install() {
        val table = OffsetTable.valueOrNull("OGlobal.SERVERPROT_TABLE") ?: return
        val count = ClientProtocol.codec?.serverProtInfo?.keys?.maxOrNull()?.plus(1) ?: run {
            println("[ServerProtDispatchCapture] no codec for this client; incoming packets are not captured")
            return
        }
        val descriptors = NativeAccess.BASE_ADDR.readLong(table)
        if (descriptors == 0L) {
            println("[ServerProtDispatchCapture] the server prot table is empty; incoming packets are not captured")
            return
        }
        val array = MemorySegment.ofAddress(descriptors).reinterpret(count * ADDRESS.byteSize())
        val slots = LinkedHashMap<String, MemorySegment>()
        for (opcode in 0 until count) {
            val descriptor = array.getAtIndex(ADDRESS, opcode.toLong())
            if (descriptor.address() == 0L) continue
            val key = "$KEY_PREFIX$opcode"
            // A descriptor shared by two opcodes must be redirected once, or its second hook adopts the first as its original.
            if (keys.putIfAbsent(descriptor.address() + OServerProtDescriptor.HANDLER, key) != null) continue
            slots[key] = descriptor.reinterpret(OServerProtDescriptor.extent)
                .asSlice(OServerProtDescriptor.HANDLER_INVOKER, ADDRESS.byteSize())
        }
        val hook = javaClass.declaredMethods.single { it.name == ::onDispatch.name }
        HookManager.hookSlots(hook, slots)
    }

    @JvmStatic
    fun onDispatch(handler: MemorySegment, packet: MemorySegment, size: MemorySegment, isaac: MemorySegment): MemorySegment {
        val key = keys[handler.address()]!!
        val captured = runCatching { read(handler, packet) }.getOrNull()
        val status = HookManager.slotOriginal(key).invokeExact(handler, packet, size, isaac) as MemorySegment
        if (captured != null && status.reinterpret(4).get(JAVA_INT, 0) != DEFERRED) {
            synchronized(Bootstrap.lock) {
                runCatching { log(captured) }.onFailure { System.err.println("[ServerProtDispatchCapture] Error: ${it.message}") }
            }
        }
        return status
    }

    private class Captured(val opcode: Int, val size: Int, val payload: ByteArray?)

    private fun read(handler: MemorySegment, packet: MemorySegment): Captured {
        val descriptor = MemorySegment.ofAddress(handler.address() - OServerProtDescriptor.HANDLER)
            .reinterpret(OServerProtDescriptor.extent)
        val opcode = descriptor.get(JAVA_INT, OServerProtDescriptor.OPCODE)
        val connection = MemorySegment.ofAddress(packet.address() - OServerConnection.PACKET_BASE)
            .reinterpret(OServerConnection.extent)
        val size = connection.get(JAVA_INT, OServerConnection.RESOLVED_SIZE)
        if (size <= 0 || size > MAX_PAYLOAD) return Captured(opcode, size, null)
        val payload = ByteArray(size)
        val data = connection.get(ADDRESS, OServerConnection.BUF_DATA).reinterpret(size.toLong())
        MemorySegment.copy(data, JAVA_BYTE, 0, payload, 0, size)
        return Captured(opcode, size, payload)
    }

    private fun log(captured: Captured) {
        when {
            captured.size <= 0 -> PacketLogger.logServerPacket(captured.opcode, captured.size, ByteArray(0))
            captured.payload == null -> PacketLogger.logServerPacketDesync(captured.opcode, captured.size, "implausible size")
            else -> {
                PacketLogger.logServerPacket(captured.opcode, captured.size, captured.payload)
                RebuildRegionMap.observe(captured.opcode, captured.payload)
            }
        }
    }
}
