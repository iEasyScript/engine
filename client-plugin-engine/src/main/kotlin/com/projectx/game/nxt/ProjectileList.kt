package com.projectx.game.nxt
import com.projectx.game.memory.atLeast

import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.eastl.EastlFixedPool
import com.projectx.game.memory.eastl.EastlFixedPoolNode
import com.projectx.game.nxt.entity.Projectile
import java.lang.foreign.MemorySegment

class ProjectileList(raw: MemorySegment) : Iterable<Projectile> {
    val ptr: MemorySegment = raw.atLeast(OProjectileList.extent)
    val pool
        get() = EastlFixedPool(ptr)

    override fun iterator(): Iterator<Projectile> = ProjectileIterator(pool.iterator())

    private class ProjectileIterator(private val nodeIterator: Iterator<EastlFixedPoolNode>) : Iterator<Projectile> {
        override fun hasNext(): Boolean = nodeIterator.hasNext()

        override fun next(): Projectile {
            val node = nodeIterator.next()
            return Projectile(node.value(0x8L).deref(size = OProjectile.extent))
        }
    }
}