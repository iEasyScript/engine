package com.projectx.game.memory

import com.projectx.game.memory.NativeAccess.readInt
import java.lang.foreign.MemorySegment

class NativeIntArray(val ptr: MemorySegment, val size: Int, val elementSize: Int) : Iterable<Int> {
    fun get(index: Int) = ptr.readInt((index * elementSize).toLong())

    override fun iterator(): Iterator<Int> {
        return object : Iterator<Int> {
            private var index = 0
            override fun hasNext() = index < size
            override fun next() = get(index++)
        }
    }
}