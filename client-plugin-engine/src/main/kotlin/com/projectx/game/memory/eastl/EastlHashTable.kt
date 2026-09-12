package com.projectx.game.memory.eastl
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OffsetObject

import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readFloat
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import java.lang.foreign.MemorySegment

open class EastlHashTable(private val ptr : MemorySegment, val elementSize: Long, val altValuesOffset: Long = 0x0L) : Iterable<MemorySegment> {
    val bucketArray
        get() = ptr.deref(OEastlHashTable.BUCKET_ARRAY, 0x20000L)
    val bucketCount
        get() = ptr.readLong(OEastlHashTable.BUCKET_COUNT)
    val elementCount
        get() = ptr.readLong(OEastlHashTable.ELEMENT_COUNT)
    val maxLoadFactor
        get() = ptr.readFloat(OEastlHashTable.MAX_LOAD_FACTOR)
    val growthFactor
        get() = ptr.readFloat(OEastlHashTable.GROWTH_FACTOR)
    val nextResize
        get() = ptr.readLong(OEastlHashTable.NEXT_RESIZE)
    val nodeSize
        get() = ptr.readLong(OEastlHashTable.NODE_SIZE)
    val values
        get() = ptr.deref(if (altValuesOffset > 0L) altValuesOffset else OEastlHashTable.VALUES_START, Short.MAX_VALUE.toLong())

    open operator fun get(hashCode: Int): MemorySegment? {
        val index = (hashCode % (bucketCount and 0xffffffffL))
        val addrOffset = index * 8L
        var currentEntry = bucketArray.pointerAtOffset(addrOffset, 0x24L)
        val specialEntry = bucketArray.pointerAtOffset(bucketCount * 8L, 0x24L)
        var maxTries = 100000
        while (maxTries-- > 0) {
            val derefed = currentEntry.deref(size = elementSize+0x10)
            if (derefed.address() == 0L) return null

            val node = EastlHashNode(derefed, elementSize)
            if (hashCode == node.hashCode)
                return if (currentEntry.address() != specialEntry.address()) node.value else null
            else
                currentEntry = node.next
        }
        return null
    }

    fun getNodeAtValuesIndex(index: Int) = EastlHashNode(values.pointerAtOffset(index * nodeSize, elementSize+0x10), elementSize)

    override fun iterator(): Iterator<MemorySegment> {
        return object : Iterator<MemorySegment> {
            private var index = 0
            override fun hasNext() = index < elementCount
            override fun next() = getNodeAtValuesIndex(index++).value
        }
    }
}

class EastlHashNode(raw: MemorySegment, val elementSize: Long) {
    val ptr: MemorySegment = raw.atLeast(OEastlHashNode.extent)
    val hashCode
        get() = ptr.readInt(OEastlHashNode.HASH_CODE)
    val value
        get() = ptr.pointerAtOffset(OEastlHashNode.VALUE, elementSize)
    val next
        get() = ptr.pointerAtOffset(elementSize-0x8L, 0x8L)
}

object OEastlHashNode : OffsetObject {
    const val HASH_CODE = 0x0L
    const val VALUE = 0x8L
}

object OEastlHashTable : OffsetObject {
    const val BUCKET_ARRAY = 0x0L
    const val BUCKET_COUNT = 0x8L
    const val ELEMENT_COUNT = 0x10L
    const val MAX_LOAD_FACTOR = 0x18L
    const val GROWTH_FACTOR = 0x1CL
    const val NEXT_RESIZE = 0x20L
    const val NODE_SIZE = 0x40L
    const val VALUES_START = 0x50L
}