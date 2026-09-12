package com.projectx.game.nxt.mainlogicmanager
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OStatTable
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.nxt.OMainLogicManager
import java.lang.foreign.MemorySegment

class MainLogicManager(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OMainLogicManager.extent)
    val statTable
        get() = StatTable(ptr.deref(OMainLogicManager.STAT_TABLE, OStatTable.extent))
    val clientVarDomain
        get() = ClientVarDomain(ptr.pointerAtOffset(OMainLogicManager.CLIENT_VAR_DOMAIN, 0x20000L))
}