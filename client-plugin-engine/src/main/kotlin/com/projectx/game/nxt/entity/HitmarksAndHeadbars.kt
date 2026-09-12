package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.eastl.EastlLinkedList
import com.projectx.game.nxt.OHeadbar
import com.projectx.game.nxt.OHit
import com.projectx.game.nxt.OHitmarksAndHeadbars
import java.lang.foreign.MemorySegment

class HitmarksAndHeadbars(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OHitmarksAndHeadbars.extent)
    val headbars
        get() = ptr.getOrNull?.let { basePtr ->
            (0..5).asSequence()
                .map { i -> EastlLinkedList(basePtr.deref(OHitmarksAndHeadbars.HEADBAR_LINKEDLIST_VECTOR_START, OHitmarksAndHeadbars.HEADBAR_STRIDE * 5).pointerAtOffset(i * OHitmarksAndHeadbars.HEADBAR_STRIDE, OHitmarksAndHeadbars.HEADBAR_STRIDE))  }
                .takeWhile { it.size > 0 }
                .flatMap { it.map { node -> Headbar(node.value(OHeadbar.extent)) } }
                .toList()
        }
    val hits
        get() = ptr.getOrNull?.let { HitArray(it.deref(OHitmarksAndHeadbars.HIT_VECTOR, VECTOR_HEADER), 8).toList() }
}

class HitArray(val ptr: MemorySegment, val size: Int) : Iterable<Hit> {
    fun get(index: Int) = Hit(ptr.pointerAtOffset(index * OHit.STRIDE, OHit.STRIDE))

    override fun iterator(): Iterator<Hit> {
        return object : Iterator<Hit> {
            private var index = 0
            override fun hasNext() = index < size
            override fun next() = get(index++)
        }
    }
}

enum class HitType(val legacy: Int, val legacyCrit: Int, val legacyOther: Int) {
    MISS(482, -1, -1),
    DODGE(141, -1, -1),
    MELEE(133, 134, 150),
    RANGED(136, 137, -1),
    MAGIC(139, 140, -1),
    TYPELESS(144, -1, -1),
    REFLECTED(146, -1, -1),
    NECROMANCY(477, 478, -1),
    NECROMANCY_CONJURE(480, -1, -1),
    UNKNOWN(-1, -1, -1);

    companion object {
        private val BY_ID = entries.associateBy(HitType::legacy) + entries.associateBy(HitType::legacyCrit) + entries.associateBy(HitType::legacyOther)
        fun byId(id: Int) = BY_ID[id] ?: UNKNOWN
    }
}

class Hit(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OHit.extent)
    val typeId
        get() = ptr.readInt(OHit.TYPE)
    val type = HitType.byId(typeId)
    val damage
        get() = ptr.readInt(OHit.DAMAGE)
    val createdClientcycle
        get() = ptr.readInt(OHit.CLIENTCYCLE_CREATED)
    val unk1
        get() = ptr.readInt(OHit.UNKNEG1_1)
    val unk2
        get() = ptr.readInt(OHit.UNKNEG1_2)
    val durationClientcycles
        get() = ptr.readInt(OHit.DURATION_CLIENTCYLES)
    val durationMillis
        get() = durationClientcycles * 20L
    val cyclesLeft
        get() = durationClientcycles - (Bootstrap.client.clientCycle - createdClientcycle)
    val timeLeftMillis
        get() = cyclesLeft * 20L

    override fun toString() = "[${if (type == HitType.UNKNOWN) "UNKNOWN($typeId)" else type}, ${damage}]"
}

class Headbar(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OHeadbar.extent)
    val typePtr
        get() = ptr.deref(OHeadbar.TYPE_PTR, 0x20L)
    val type
        get() = typePtr.readInt(OHeadbar.TYPE_ID)
    val createdClientcycle
        get() = ptr.readInt(OHeadbar.CLIENTCYCLE_CREATED)
    val fromFill
        get() = ptr.readInt(OHeadbar.FROM_FILL)
    val toFill
        get() = ptr.readInt(OHeadbar.TO_FILL)
    val durationClientcycles
        get() = ptr.readInt(OHeadbar.DURATION_CLIENTCYCLES)
    val durationMillis
        get() = durationClientcycles * 20L
    val cyclesLeft
        get() = durationClientcycles - (Bootstrap.client.clientCycle - createdClientcycle)
    val timeLeftMillis
        get() = cyclesLeft * 20L
}

/** begin, end and capacity of an eastl vector. */
private const val VECTOR_HEADER = 0x20L
