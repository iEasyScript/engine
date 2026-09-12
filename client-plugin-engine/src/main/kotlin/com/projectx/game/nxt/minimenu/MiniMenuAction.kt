package com.projectx.game.nxt.minimenu
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.OMiniMenuAction
import com.projectx.game.platform.Platform
import java.lang.foreign.MemorySegment

class MiniMenuAction(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(OMiniMenuAction.extent)
    val id: Int
        get() = ptr.readInt(OMiniMenuAction.ACTION_ID)

    /**
     * MSVC keeps the callable's invoke slot in a vtable the action points at, while the GCC build
     * stores that pointer inline in the action itself — only Windows takes the extra hop.
     */
    val actionSendFunc: MemorySegment
        get() = when (Platform.current) {
            Platform.WINDOWS ->
                ptr.deref(OMiniMenuAction.ACTION_VTABLE, OMiniMenuAction.VTABLE_ACTION_SEND_FUNCTION + 8)
                    .deref(OMiniMenuAction.VTABLE_ACTION_SEND_FUNCTION, 0L)
            else -> ptr.deref(OMiniMenuAction.VTABLE_ACTION_SEND_FUNCTION, 0L)
        }
}
