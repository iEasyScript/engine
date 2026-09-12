package com.projectx.game.nxt.mainlogicmanager
import com.projectx.game.memory.atLeast
import com.projectx.game.memory.eastl.OEastlHashTable
import com.projectx.game.nxt.extent

import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.eastl.EastlHashTable
import com.projectx.game.nxt.OClientVarDomain
import org.projectx.core.game.combat.VarReader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.VarBitType.Companion.BIT_MASKS
import java.lang.foreign.MemorySegment

class ClientVarDomain(raw: MemorySegment) : VarReader {
    val ptr: MemorySegment = raw.atLeast(OClientVarDomain.extent)
    val hashTable
        get() = EastlHashTable(ptr.pointerAtOffset(OClientVarDomain.HASH_TABLE, OEastlHashTable.extent), 0x30, 0x30)

    override fun getVar(id: Int) = hashTable[id]?.readInt() ?: 0

    override fun getVarLong(id: Int): Long {
        val slot = hashTable[id] ?: return 0
        return if (Cache.varc(id)?.long == true) slot.readLong() else slot.readInt().toLong()
    }

    override fun getVarBit(id: Int): Int {
        try {
            val type = Cache.varbit(id) ?: return 0
            return getVar(type.baseVar) shr type.startBit and BIT_MASKS[type.endBit - type.startBit]
        } catch(e: Throwable) {
            return 0
        }
    }
}