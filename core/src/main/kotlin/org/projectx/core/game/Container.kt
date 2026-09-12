package org.projectx.core.game

interface Container {
    val id: Int
    val capacity: Int

    operator fun get(slot: Int): Obj?
    fun set(slot: Int, obj: Obj?)

    fun add(obj: Obj): Boolean
    fun add(id: Int, amount: Int = 1): Boolean
    fun remove(id: Int, amount: Int = 1): Boolean
    fun removeAt(slot: Int, amount: Int = Int.MAX_VALUE): Obj?

    fun count(id: Int): Int
    fun contains(id: Int, amount: Int = 1): Boolean

    fun slotOf(id: Int): Int
    fun firstFreeSlot(): Int
    fun freeSlots(): Int
    fun freeSlotsFor(obj: Obj): Boolean
    fun isFull(): Boolean
    fun isEmpty(): Boolean
    fun clear()

    fun swap(from: Int, to: Int)

    fun takeDirty(): DirtyState
}

sealed interface DirtyState {
    data object Clean : DirtyState
    data object Full : DirtyState
    data class Slots(val slots: Set<Int>) : DirtyState
}
