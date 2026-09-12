package com.projectx.game.hooks.impl

import com.projectx.game.cs2.CS2Executor
import com.projectx.game.cs2.CS2Trace
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.nxt.OFunctions
import java.lang.foreign.MemorySegment

/**
 * Captures the live `jag::ScriptRunner*` and the per-run `ClientScriptState*` off every script
 * execution. ExecuteScript(scriptRunner, clientScript, maxSteps, state) is the single point that
 * sees both: param_1 is the runner CS2Executor needs to drive [CS2Executor.executeScript], and
 * param_4 is the script state whose operand stacks hold a script's return values once it finishes.
 */
object ScriptRunnerCapture {
    @JvmStatic
    @Hook("SCRIPTRUNNER_EXECUTESCRIPT")
    fun executeScriptHook(
        scriptRunner: MemorySegment,
        clientScript: MemorySegment,
        maxSteps: Int,
        state: MemorySegment
    ) {
        var pending: CS2Executor.TracePending? = null
        try {
            CS2Executor.onExecuteScript(scriptRunner, state)
            if (CS2Trace.enabled) pending = CS2Executor.beginTrace(clientScript, state)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        HookManager.trampoline(::executeScriptHook.name).invoke(scriptRunner, clientScript, maxSteps, state)
        if (pending != null) {
            try {
                CS2Executor.endTrace(pending, state)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}
