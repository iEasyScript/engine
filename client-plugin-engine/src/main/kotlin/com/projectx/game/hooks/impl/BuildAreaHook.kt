package com.projectx.game.hooks.impl

import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.nxt.OFunctions
import com.projectx.pathfinder.DynamicMapSquareCollision
import java.lang.foreign.MemorySegment

/**
 * Hooks MapSquare::Init (BUILD_AREA_INIT), called from both REBUILD_NORMAL and REBUILD_REGION. Fires
 * for every build area initialization — including incremental updates like opening dungeoneering doors.
 */
object BuildAreaHook {

    @Volatile
    var dirty = false
        private set

    fun consumeDirty(): Boolean {
        if (!dirty) return false
        dirty = false
        return true
    }

    @JvmStatic
    @Hook("BUILD_AREA_INIT")
    fun buildAreaInitHook(structPtr: MemorySegment, mapSquareId: Int) {
        HookManager.trampoline(::buildAreaInitHook.name)
            .invoke(structPtr, mapSquareId)

        dirty = true
        DynamicMapSquareCollision.markDirty()
    }
}
