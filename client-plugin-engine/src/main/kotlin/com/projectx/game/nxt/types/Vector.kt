package com.projectx.game.nxt.types

import com.projectx.game.memory.NativeAccess.deref
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

class Vector(val ptr: MemorySegment, private val elementSize: Long = 0x8L) : Iterable<MemorySegment> {
    val begin: MemorySegment
    val end: MemorySegment
    val capacity: MemorySegment

    init {
        begin = ptr.deref(OVector.VECTOR_BEGIN, elementSize * 4096L)
        end = ptr.deref(OVector.VECTOR_END, elementSize * 4096L)
        capacity = ptr.deref(OVector.VECTOR_CAPACITY, elementSize * 4096L)
    }

    val size: Long
        get() = (end.address() - begin.address()) / elementSize

    val bytes: Long
        get() = end.address() - begin.address()

    operator fun get(index: Int): MemorySegment {
        return begin.asSlice(index * elementSize, ValueLayout.ADDRESS).reinterpret(elementSize)
    }

    override fun iterator(): Iterator<MemorySegment> {
        return object : Iterator<MemorySegment> {
            private var index = 0
            override fun hasNext(): Boolean {
                return index < size
            }

            override fun next(): MemorySegment {
                return get(index++)
            }
        }
    }

    object OVector {
        const val VECTOR_BEGIN = 0x0L
        const val VECTOR_END = 0x8L
        const val VECTOR_CAPACITY = 0x10L
    }
}