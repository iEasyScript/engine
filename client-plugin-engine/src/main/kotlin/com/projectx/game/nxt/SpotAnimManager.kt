package com.projectx.game.nxt
import com.projectx.game.memory.atLeast

import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readLong
import java.lang.foreign.MemorySegment

class SpotAnimManager(internal val ptr: MemorySegment) : Iterable<MemorySegment> {
    val vtable
        get() = ptr.pointerAtOffset(OSpotAnimManager.VTABLE, 0x8L)
    val firstNode
        get() = ptr.deref(OSpotAnimManager.FIRST_NODE, 0x18L)
    val listSize
        get() = ptr.readLong(OSpotAnimManager.LIST_SIZE)

    override fun iterator(): Iterator<MemorySegment> {
        return object : Iterator<MemorySegment> {
            private var currentNode = firstNode
            private val endNode = ptr.pointerAtOffset(OSpotAnimManager.FIRST_NODE, 0x18L)

            override fun hasNext() = currentNode.address() != endNode.address()

            override fun next(): MemorySegment {
                if (!hasNext()) throw NoSuchElementException()

                val node = SpotAnimNode(currentNode)
                currentNode = node.next
                return node.value
            }
        }
    }
}

class SpotAnimNode(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OSpotAnimNode.extent)
    val next
        get() = ptr.deref(OSpotAnimNode.NEXT, 0x18L)
    val value
        get() = ptr.pointerAtOffset(OSpotAnimNode.VALUE, OSpotAnim.extent)
}