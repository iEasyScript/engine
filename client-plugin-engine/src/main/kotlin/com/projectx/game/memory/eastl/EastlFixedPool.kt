package com.projectx.game.memory.eastl
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OffsetObject

import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readLong
import java.lang.foreign.MemorySegment

class EastlFixedPool(raw: MemorySegment, val startOffset: Long = OEastlFixedPool.START, val endOffset: Long = OEastlFixedPool.END, val sizeOffset: Long = OEastlFixedPool.SIZE) : Iterable<EastlFixedPoolNode> {
    val ptr: MemorySegment = raw.atLeast(OEastlFixedPool.extent)
    val start
        get() = ptr.deref(OEastlFixedPool.START, 0x18L)
    val end
        get() = ptr.deref(OEastlFixedPool.END, 0x18L)
    val size
        get() = ptr.readLong(OEastlFixedPool.SIZE)

    override fun iterator(): Iterator<EastlFixedPoolNode> = EastlFixedPoolIterator(this)

    private class EastlFixedPoolIterator(private val pool: EastlFixedPool) : Iterator<EastlFixedPoolNode> {
        private var current = EastlFixedPoolNode(pool.start)
        private var isFirst = true

        override fun hasNext() = ((isFirst || current.ptr.address() != pool.end.address()) && pool.size > 0)

        override fun next(): EastlFixedPoolNode {
            if (!hasNext()) throw NoSuchElementException()
            if (isFirst) {
                isFirst = false
                return current
            }
            current = current.next
            return current
        }
    }
}

object OEastlFixedPool : OffsetObject {
    const val START = 0x10L
    const val END = 0x18L
    const val SIZE = 0x50L
}

class EastlFixedPoolNode(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OEastlFixedPoolNode.extent)
    val next
        get() = EastlFixedPoolNode(ptr.deref(OEastlFixedPoolNode.NEXT, 0x18L))
    val prev
        get() = EastlFixedPoolNode(ptr.deref(OEastlFixedPoolNode.PREV, 0x18L))
    fun value(size: Long) = ptr.pointerAtOffset(OEastlFixedPoolNode.VALUE, size)
}

object OEastlFixedPoolNode : OffsetObject {
    const val NEXT = 0x0L
    const val PREV = 0x8L
    const val VALUE = 0x10L
}