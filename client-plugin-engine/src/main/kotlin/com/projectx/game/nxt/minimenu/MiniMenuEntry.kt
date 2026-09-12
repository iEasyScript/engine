package com.projectx.game.nxt.minimenu
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.entity.EntityFactory

import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OMiniMenuAction
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getInt
import com.projectx.game.memory.NativeAccess.getLong
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.eastl.EastlString
import com.projectx.game.nxt.OMiniMenuEntry
import java.lang.foreign.MemorySegment

class MiniMenuEntry(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OMiniMenuEntry.extent)
    val targetString: EastlString
        get() = EastlString(ptr.pointerAtOffset(OMiniMenuEntry.TARGET_STRING, 0x24L))
    val actionString: EastlString
        get() = EastlString(ptr.pointerAtOffset(OMiniMenuEntry.ACTION_STRING, 0x24L))
    val highlightType: Long
        get() = ptr.getLong(OMiniMenuEntry.HIGHLIGHT_TYPE)
    val action: MiniMenuAction
        get() = MiniMenuAction(ptr.deref(OMiniMenuEntry.ACTION, OMiniMenuAction.extent))
    val unk1: Int
        get() = ptr.getInt(OMiniMenuEntry.UNK_1)
    val itemId: Int
        get() = ptr.getInt(OMiniMenuEntry.ITEM_ID)
    val param1: Int
        get() = ptr.getInt(OMiniMenuEntry.PARAM_1)
    val param2: Int
        get() = ptr.getInt(OMiniMenuEntry.PARAM_2)
    val param3: Int
        get() = ptr.getInt(OMiniMenuEntry.PARAM_3)
    val target: MemorySegment
        get() = ptr.pointerAtOffset(OMiniMenuEntry.TARGETED_ENTITY, EntityFactory.extent)
}