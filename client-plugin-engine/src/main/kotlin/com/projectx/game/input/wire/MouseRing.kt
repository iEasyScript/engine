package com.projectx.game.input.wire

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.nxt.OClient
import com.projectx.game.nxt.OMainLogicManager
import com.projectx.game.nxt.OMouseSample
import com.projectx.game.nxt.OffsetUnavailableException
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG

/**
 * The two rings the client stages mouse input in, both on MainLogicManager and both holding the same 40-byte
 * sample: a small **press** ring drained one entry per tick by the click sender, and a much larger **movement**
 * ring drained in a loop by the movement-history sender.
 *
 * Each ring is followed by its own cursor pair - **write first, read second** - and is empty when they are
 * equal. That ordering was verified in three places: the click sender indexes with the second dword, the
 * movement pop advances the second, and the movement producer writes at the first and advances it. The engine
 * previously named them the other way round, which made its injection write one slot behind the slot the
 * sender then read.
 *
 * A sample's `button` is a button **id** for a press (zero is the left button) and **-1** for a pure movement
 * sample. There is no "moved without clicking" value in the press ring - that is why a cursor trail has to go
 * through the movement ring.
 */
internal class MouseRing private constructor(
    private val manager: MemorySegment,
    private val base: Long,
    private val writeCursor: Long,
    private val readCursor: Long,
    val capacity: Int,
    private val stride: Long,
) {
    val write: Int get() = manager.get(JAVA_INT, writeCursor)
    val read: Int get() = manager.get(JAVA_INT, readCursor)

    val isEmpty: Boolean get() = write == read

    fun pending(): Int = ((write - read) % capacity + capacity) % capacity

    fun slotOffset(index: Int): Long = base + wrap(index) * stride

    fun wrap(index: Int): Int = ((index % capacity) + capacity) % capacity

    fun advance(index: Int): Int = wrap(index + 1)

    fun setWrite(value: Int) = manager.set(JAVA_INT, writeCursor, value)

    fun setRead(value: Int) = manager.set(JAVA_INT, readCursor, value)

    fun snapshot(index: Int): Sample {
        val at = slotOffset(index)
        return Sample(
            button = manager.get(JAVA_INT, at + OMouseSample.BUTTON),
            x = manager.get(JAVA_FLOAT, at + OMouseSample.X),
            y = manager.get(JAVA_FLOAT, at + OMouseSample.Y),
            timestampMs = manager.get(JAVA_LONG, at + OMouseSample.TIMESTAMP_MS),
        )
    }

    fun restore(index: Int, sample: Sample) = write(index, sample)

    fun write(index: Int, sample: Sample) {
        val at = slotOffset(index)
        manager.set(JAVA_INT, at + OMouseSample.BUTTON, sample.button)
        manager.set(JAVA_FLOAT, at + OMouseSample.X, sample.x)
        manager.set(JAVA_FLOAT, at + OMouseSample.Y, sample.y)
        manager.set(JAVA_LONG, at + OMouseSample.TIMESTAMP_MS, sample.timestampMs)
    }

    data class Sample(val button: Int, val x: Float, val y: Float, val timestampMs: Long) {
        /** A never-written slot reads back fully zeroed; the client never enqueues one. */
        fun isEmptySlot(): Boolean = button == 0 && x == 0f && y == 0f && timestampMs == 0L

        companion object {
            /** The value the client's own producer stores for a sample that carries position only. */
            const val MOVEMENT_ONLY = -1
        }
    }

    companion object {
        fun clicks(): MouseRing? = whereTabled {
            of(
                OMainLogicManager.CLICK_BUFFER_BASE,
                OMainLogicManager.CLICK_BUFFER_WRITE,
                OMainLogicManager.CLICK_BUFFER_READ,
                OMainLogicManager.CLICK_BUFFER_CAPACITY,
                OMainLogicManager.CLICK_ENTRY_SIZE,
            )
        }

        fun movement(): MouseRing? = whereTabled {
            of(
                OMainLogicManager.MOVE_BUFFER_BASE,
                OMainLogicManager.MOVE_BUFFER_WRITE,
                OMainLogicManager.MOVE_BUFFER_READ,
                OMainLogicManager.MOVE_BUFFER_CAPACITY,
                OMainLogicManager.MOVE_ENTRY_SIZE,
            )
        }

        /**
         * A ring absent from this platform's offset table is "unreachable", not an error - callers probe for
         * null to decide whether the wire path can run at all. The reads have to happen inside the guard:
         * as [of] arguments they are evaluated before the callee's own catch can cover them.
         */
        private inline fun whereTabled(read: () -> MouseRing?): MouseRing? = try {
            read()
        } catch (_: OffsetUnavailableException) {
            null
        }

        private fun of(base: Long, write: Long, read: Long, capacity: Int, stride: Long): MouseRing? = try {
            managerOrNull(read + 4L)?.let { MouseRing(it, base, write, read, capacity, stride) }
        } catch (_: Throwable) {
            null
        }

        private fun managerOrNull(extent: Long): MemorySegment? = try {
            val address = Bootstrap.client.ptr.get(JAVA_LONG, OClient.MAINLOGIC_MANAGER)
            if (address == 0L) null else MemorySegment.ofAddress(address).reinterpret(extent)
        } catch (_: Throwable) {
            null
        }
    }
}
