package com.projectx.game.input.wire

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess
import com.projectx.game.nxt.OClient
import com.projectx.game.nxt.OInterfaceManager
import com.projectx.game.nxt.OKeyEvent
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * Puts synthetic key presses on the wire without touching any of the client's keyboard state.
 *
 * The keyboard packet is **not** built from the key ring. It is built from the interface manager's per-tick
 * key-event vector, which the manager's own input pass refills from the ring each tick. So a press can be delivered
 * by pointing that vector at records the engine owns for the duration of one flush — the ring, the per-key
 * held and seen arrays, the modifier masks and the CS2 dispatch are never involved, which is what makes this
 * genuinely server-only.
 *
 * Presses only. The client's key-up delegate records nothing, so there is no release on this path and nothing
 * to encode for one. `repeat` must be clear or the packet builder filters the record out, and only the low
 * byte of the key id reaches the wire — as the client's own **post-remap protocol** id, not a platform code.
 */
internal object KeyPressSender {
    /** The packet's length field is a byte count of `records * 4`, so this stays well inside it. */
    private const val MAX_RECORDS = 32

    private val scratch: MemorySegment by lazy {
        NativeAccess.engineArena.allocate(OKeyEvent.SIZE * MAX_RECORDS, 8)
    }

    private val queued = ArrayDeque<Press>()

    @Volatile var packetsSent: Long = 0
        private set
    @Volatile var pressesSent: Long = 0
        private set
    @Volatile var skippedRealInput: Long = 0
        private set
    @Volatile var droppedOverflow: Long = 0
        private set

    val queuedPresses: Int get() = synchronized(queued) { queued.size }

    data class Press(val protocolKeyId: Int, val timestampMs: Long)

    fun offer(presses: List<Press>) {
        if (presses.isEmpty()) return
        synchronized(queued) {
            queued.addAll(presses)
            while (queued.size > MAX_RECORDS) {
                queued.removeFirst()
                droppedOverflow++
            }
        }
    }

    private var swappedManager: MemorySegment? = null
    private var savedBegin: Long = 0
    private var savedEnd: Long = 0
    private var swappedCount: Int = 0

    /**
     * Point the tick's key-event vector at engine-owned records. Returns true only when a swap was installed,
     * in which case [endSwap] **must** run afterwards.
     *
     * Deliberately split from the flush rather than wrapping it: the caller has to keep its `invokeExact` in
     * statement position, and a wrapper that returns the flush's value makes that impossible.
     *
     * The swap is skipped entirely when the player has real key input staged this tick, because overwriting
     * the vector would drop their keystrokes rather than add to them.
     */
    fun beginSwap(): Boolean {
        val manager = managerOrNull() ?: return false
        val pending = synchronized(queued) { if (queued.isEmpty()) null else queued.toList() } ?: return false

        val begin = manager.get(JAVA_LONG, OInterfaceManager.KEY_EVENT_BEGIN)
        val end = manager.get(JAVA_LONG, OInterfaceManager.KEY_EVENT_END)
        if (begin != end) {
            skippedRealInput++
            return false
        }

        val count = minOf(pending.size, MAX_RECORDS)
        for (i in 0 until count) writeRecord(i, pending[i])

        swappedManager = manager
        savedBegin = begin
        savedEnd = end
        swappedCount = count
        manager.set(JAVA_LONG, OInterfaceManager.KEY_EVENT_BEGIN, scratch.address())
        manager.set(JAVA_LONG, OInterfaceManager.KEY_EVENT_END, scratch.address() + OKeyEvent.SIZE * count)
        return true
    }

    /** Put the vector back exactly as it was. Safe to call only after [beginSwap] returned true. */
    fun endSwap() {
        val manager = swappedManager ?: return
        manager.set(JAVA_LONG, OInterfaceManager.KEY_EVENT_BEGIN, savedBegin)
        manager.set(JAVA_LONG, OInterfaceManager.KEY_EVENT_END, savedEnd)
        swappedManager = null
        packetsSent++
        pressesSent += swappedCount
        synchronized(queued) { repeat(minOf(swappedCount, queued.size)) { queued.removeFirst() } }
        swappedCount = 0
    }

    private fun writeRecord(index: Int, press: Press) {
        val at = OKeyEvent.SIZE * index
        scratch.set(JAVA_INT, at + OKeyEvent.KEY_ID, press.protocolKeyId)
        scratch.set(JAVA_INT, at + OKeyEvent.CHAR_CODE, 0)
        scratch.set(JAVA_INT, at + OKeyEvent.RAW_KEY_CODE, press.protocolKeyId)
        // Cleared, or the packet builder filters the record out as an auto-repeat.
        scratch.set(JAVA_INT, at + OKeyEvent.REPEAT, 0)
        scratch.set(JAVA_INT, at + OKeyEvent.MODIFIER_MASK, 0)
        scratch.set(JAVA_LONG, at + OKeyEvent.TIMESTAMP_MS, press.timestampMs)
    }

    private fun managerOrNull(): MemorySegment? = try {
        val address = Bootstrap.client.ptr.get(JAVA_LONG, OClient.INTERFACE_MANAGER)
        if (address == 0L) null
        else MemorySegment.ofAddress(address).reinterpret(OInterfaceManager.KEY_EVENT_END + 8L)
    } catch (_: Throwable) {
        null
    }
}
