package com.projectx.game.hooks.impl

import java.lang.foreign.MemorySegment

@Deprecated("The 951 Windows clients have no SetMainState function to hook; MainStateWatch raises MainStateChanged instead.")
object ClientSetGameState {
    @Deprecated("No longer installed; kept for binary compatibility.")
    @JvmStatic
    fun addClientSetMainStateHook(clientMainPtr: MemorySegment, state: Int) = Unit
}
