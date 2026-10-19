package com.projectx.game.bootstrap

import com.projectx.game.nxt.MainState
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.timeLoggedInAt
import com.projectx.script.event.impl.MainStateChanged

/**
 * Raises [MainStateChanged] when the client's main state moves. Read once per main-logic tick rather than hooked,
 * because the 951 Windows clients inline SetMainState into every caller and leave no function to hook.
 */
internal object MainStateWatch {
    private var last: Int? = null

    fun tick() {
        val state = Bootstrap.client.mainState
        val previous = last
        last = state
        if (previous == null || previous == state) return
        if (state == MainState.LOGGED_IN) timeLoggedInAt = System.currentTimeMillis()
        ScriptExecutor.pushEvent(MainStateChanged(state))
    }
}
