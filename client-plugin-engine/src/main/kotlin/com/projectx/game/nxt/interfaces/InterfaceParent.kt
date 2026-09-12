package com.projectx.game.nxt.interfaces

import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OInterfaceComponent
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.NativeAccess.toMemorySegment
import com.projectx.game.nxt.OInterfaceParent
import java.lang.foreign.MemorySegment

class InterfaceParent(val id: Int, val ptr: MemorySegment) : Iterable<InterfaceComponent> {
    private val struct: MemorySegment = ptr.reinterpret(STRUCT_BYTES)
    private val begin: Long get() = struct.readLong(OInterfaceParent.CHILD_ARRAY_BEGIN)
    private val end: Long get() = struct.readLong(OInterfaceParent.CHILD_ARRAY_END)

    val size: Int
        get() {
            val b = begin
            val e = end
            if (b == 0L || e == 0L || e < b) return 0
            val count = (e - b) / OInterfaceParent.CHILD_SLOT_STRIDE
            // A span that implies more slots than any interface has means these bytes are not a slot vector
            // at all - reading a non-parent component's memory here yields an arbitrary begin/end pair, and
            // walking it drags the client through gigabytes of addresses before it faults.
            if (count > MAX_PLAUSIBLE_SLOTS) return 0
            return count.toInt()
        }

    operator fun get(componentId: Int): InterfaceComponent? {
        if (componentId < 0) return null
        val b = begin
        val e = end
        if (b == 0L || e == 0L) return null
        val slotAddr = b + componentId * OInterfaceParent.CHILD_SLOT_STRIDE
        if (slotAddr + OInterfaceParent.CHILD_SLOT_STRIDE > e) return null
        val slot = slotAddr.toMemorySegment(OInterfaceParent.CHILD_SLOT_STRIDE)
        if (slot.readByte(OInterfaceParent.CHILD_SLOT_HIDDEN_FLAG) != 0.toByte()) return null
        val compAddr = slot.readLong(OInterfaceParent.CHILD_SLOT_PTR)
        if (compAddr == 0L) return null
        return InterfaceComponent(compAddr.toMemorySegment(OInterfaceComponent.extent))
    }

    override fun iterator(): Iterator<InterfaceComponent> = object : Iterator<InterfaceComponent> {
        private val total = size
        private var index = 0
        private var pending: InterfaceComponent? = null

        private fun advance() {
            while (index < total) {
                val c = get(index)
                index++
                if (c != null) {
                    pending = c
                    return
                }
            }
            pending = null
        }

        override fun hasNext(): Boolean {
            if (pending != null) return true
            advance()
            return pending != null
        }

        override fun next(): InterfaceComponent {
            if (pending == null) advance()
            val v = pending ?: throw NoSuchElementException()
            pending = null
            return v
        }
    }

    private companion object {
        const val STRUCT_BYTES = 0x40L
    }
}
