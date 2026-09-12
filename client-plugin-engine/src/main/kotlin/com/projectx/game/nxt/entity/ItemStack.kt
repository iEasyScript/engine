package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.OItemStack
import com.projectx.game.nxt.types.Vector
import java.lang.foreign.MemorySegment

class ItemStack(raw: MemorySegment) : Entity(raw) {
    val itemVector
        get() = Vector(ptr.pointerAtOffset(OItemStack.ITEM_VECTOR, 0x20L), OItemStack.ITEM_VECTOR_ELEM_SIZE)
    val groundItems
        get() = itemVector.map { GroundItem(it.readInt(OItemStack.ITEM_ID), it.readInt(OItemStack.ITEM_AMOUNT), tile) }
    override val size get() = 0
}