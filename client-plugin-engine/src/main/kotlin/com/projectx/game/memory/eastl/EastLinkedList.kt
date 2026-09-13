package com.projectx.game.memory.eastl
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OffsetObject

import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readInt
import java.lang.foreign.MemorySegment

object OEastlLinkedList : OffsetObject {
    const val SIZE = 0x40L
}

class EastlLinkedList(raw: MemorySegment) : Iterable<EastlLinkedListNode> {
    val ptr: MemorySegment = raw.atLeast(OEastlLinkedList.extent)
    val startPtr
        get() = ptr.deref(0x0, NODE_SIZE)
    val size
        get() = ptr.readInt(OEastlLinkedList.SIZE)

    override fun iterator(): Iterator<EastlLinkedListNode> {
        return object : Iterator<EastlLinkedListNode> {
            // The list object is its own sentinel: an empty list points at itself, and the last node points back to it.
            private var currentNode: EastlLinkedListNode? = if (isListEmpty()) null else EastlLinkedListNode(startPtr)

            private fun isListEmpty() = startPtr.address() == ptr.address()

            override fun hasNext() = currentNode != null

            override fun next(): EastlLinkedListNode {
                val node = currentNode ?: throw NoSuchElementException("No more elements")
                val nextPtr = node.next.ptr
                currentNode = if (nextPtr.address() == 0L || nextPtr.address() == ptr.address()) null else EastlLinkedListNode(nextPtr)
                return node
            }
        }
    }
}

object OEastlLinkedListNode : OffsetObject {
    const val NEXT = 0x0L
    const val PREV = 0x8L
    const val VALUE = 0x10L
}

class EastlLinkedListNode(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OEastlLinkedListNode.extent)
    val next
        get() = EastlLinkedListNode(ptr.deref(OEastlLinkedListNode.NEXT, 0x18L))
    val prev
        get() = EastlLinkedListNode(ptr.deref(OEastlLinkedListNode.PREV, 0x18L))
    fun value(size: Long) = ptr.pointerAtOffset(OEastlLinkedListNode.VALUE, size)
}

/** next, prev and the value pointer. */
private const val NODE_SIZE = 0x18L
