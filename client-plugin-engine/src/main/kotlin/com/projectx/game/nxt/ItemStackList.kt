package com.projectx.game.nxt
import com.projectx.game.memory.atLeast

import world.gregs.voidps.type.Tile
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.entity.ItemStack
import java.lang.foreign.MemorySegment

class ItemStackList(raw: MemorySegment) : Iterable<MemorySegment> {
    val ptr: MemorySegment = raw.atLeast(OItemStackList.extent)
    val rbTreeBase
        get() = ptr.deref(OItemStackList.RB_TREE_BASE, OItemStackNode.extent)

    override fun iterator(): Iterator<MemorySegment> {
        return object : Iterator<MemorySegment> {
            private var current = findLeftmostNode(rbTreeBase)
            private val treeRoot = rbTreeBase

            override fun hasNext() = current != MemorySegment.NULL

            override fun next(): MemorySegment {
                if (!hasNext()) throw NoSuchElementException()

                val result = current
                current = getNextNode(current)
                return result
            }

            private fun findLeftmostNode(root: MemorySegment): MemorySegment {
                if (root == MemorySegment.NULL) return MemorySegment.NULL
                var current = root
                var leftChild = current.deref(OItemStackNode.LEFT, OItemStackNode.extent)
                while (leftChild != MemorySegment.NULL) {
                    current = leftChild
                    leftChild = current.deref(OItemStackNode.LEFT, OItemStackNode.extent)
                }
                return current
            }

            private fun getNextNode(node: MemorySegment): MemorySegment {
                val rightChild = node.deref(OItemStackNode.RIGHT, OItemStackNode.extent)
                if (rightChild != MemorySegment.NULL) {
                    return findLeftmostNode(rightChild)
                }

                var current = node
                var parent = current.deref(OItemStackNode.PARENT, OItemStackNode.extent)

                while (parent != MemorySegment.NULL) {
                    if (current.address() == treeRoot.address())
                        return MemorySegment.NULL

                    val parentLeft = parent.deref(OItemStackNode.LEFT, OItemStackNode.extent)
                    if (parentLeft.address() == current.address())
                        return parent

                    current = parent
                    parent = current.deref(OItemStackNode.PARENT, OItemStackNode.extent)
                }

                return MemorySegment.NULL
            }
        }
    }

    val allGroundItems
        get() = this.map { ItemStackNode(it).itemStack.groundItems }.flatten()
}

class ItemStackNode(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OItemStackNode.extent)
    val left
        get() = ptr.deref(OItemStackNode.LEFT, OItemStackNode.extent)
    val right
        get() = ptr.deref(OItemStackNode.RIGHT, OItemStackNode.extent)
    val parent
        get() = ptr.deref(OItemStackNode.PARENT, OItemStackNode.extent)
    val tile: Tile
        get() = Tile.of(ptr.readInt(OItemStackNode.POS_X), ptr.readInt(OItemStackNode.POS_Y), ptr.readInt(OItemStackNode.POS_PLANE))
    val itemStack
        get() = ItemStack(ptr.deref(OItemStackNode.ITEM_STACK, OItemStack.extent))
}