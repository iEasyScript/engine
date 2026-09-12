package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.nxt.OFunctions
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.timeLoggedInAt
import com.projectx.script.event.impl.MainStateChanged
import java.lang.foreign.MemorySegment

object ClientSetGameState {
    @JvmStatic
    @Hook("CLIENT_SETMAINSTATE")
    fun addClientSetMainStateHook(clientMainPtr: MemorySegment, state: Int) {
        synchronized(Bootstrap.lock) {
            try {
                ScriptExecutor.pushEvent(MainStateChanged(state))
            } catch (t : Throwable) {
                t.printStackTrace()
            }
            if (state == 30) timeLoggedInAt = System.currentTimeMillis()
            HookManager.trampoline(::addClientSetMainStateHook.name).invoke(clientMainPtr, state)
        }
    }
}