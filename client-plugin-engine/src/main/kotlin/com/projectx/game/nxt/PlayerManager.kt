package com.projectx.game.nxt
import com.projectx.game.memory.atLeast

import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.nxt.types.Vector
import java.lang.foreign.MemorySegment

class PlayerManager(raw: MemorySegment) : Iterable<MemorySegment> {
    val ptr: MemorySegment = raw.atLeast(OPlayerManager.extent)
    val players = Vector(ptr.pointerAtOffset(OPlayerManager.PLAYER_LIST, 0x20L))

    operator fun get(index: Int): MemorySegment {
        val node = players[index].deref(size = 0x40L)
        if(node.address() == 0L) {
            return MemorySegment.NULL
        }
        return node.pointerAtOffset(OPlayerManager.PLAYER_LIST_NODE_ENTITY, 0x10L).toShared().value(OPathingEntity.extent)
    }

    override fun iterator(): Iterator<MemorySegment> {
        return object : Iterator<MemorySegment> {
            private var index = 1 // player list index 0 is always empty
            override fun hasNext(): Boolean {
                return index < players.size
            }

            override fun next(): MemorySegment {
                return get(index++)
            }
        }
    }
}