package com.projectx.game.nxt
import com.projectx.game.memory.atLeast
import com.projectx.game.memory.eastl.OEastlHashTable

import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.memory.NativeIntArray
import com.projectx.game.memory.eastl.EastlHashTable
import java.lang.foreign.MemorySegment

class NPCManager(raw: MemorySegment) : Iterable<MemorySegment> {
    val ptr: MemorySegment = raw.atLeast(ONPCManager.extent)
    val hashTable = EastlHashTable(ptr.pointerAtOffset(ONPCManager.HASH_TABLE, OEastlHashTable.extent), ONPCManager.HASH_ELEMENT_SIZE)
    val indices = NativeIntArray(ptr.pointerAtOffset(ONPCManager.LOCAL_NPC_INDICES, 1024*0x4), 1024, 0x4)

    operator fun get(hashCode: Int): MemorySegment? = hashTable[hashCode]?.let { return it.toShared().value(ONPC.extent) }

    // The EASTL hash-table value-index walk comes back empty in instanced areas even when keyed
    // lookups succeed, so we iterate the scene-correct local index array instead.
    override fun iterator(): Iterator<MemorySegment> {
        val out = ArrayList<MemorySegment>()
        for (hashCode in indices) {
            if (hashCode <= 0) continue
            val seg = get(hashCode) ?: continue
            if (seg.address() != 0L) out.add(seg)
        }
        return out.iterator()
    }
}