package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getInt
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OVarInfo
import com.projectx.script.ScriptExecutor
import com.projectx.script.event.impl.Varp
import com.projectx.script.event.impl.Varpbit
import com.projectx.ui.UIState
import world.gregs.voidps.cache.type.data.VarBitType
import world.gregs.voidps.cache.type.data.VarDomain
import java.lang.foreign.MemorySegment

/** 951 added a fourth int to both setters; taking and passing it through is harmless where the client ignores it. */
object SetVarPlayer {
    private const val FROM_SERVER_HOOK = "setVarValueFromServerHook"
    private const val SET_HOOK = "setVarValueHook"

    @Deprecated("The client calls the four-argument form.")
    @JvmStatic
    fun setVarValueFromServerHook(varManager: MemorySegment, varInfo: MemorySegment, valuePtr: MemorySegment): MemorySegment =
        setVarValueFromServerHook(varManager, varInfo, valuePtr, -1)

    @Deprecated("The client calls the four-argument form.")
    @JvmStatic
    fun setVarValueHook(varManager: MemorySegment, varInfo: MemorySegment, valuePtr: MemorySegment): MemorySegment =
        setVarValueHook(varManager, varInfo, valuePtr, -1)

    @JvmStatic
    @Hook("PLAYERVARDOMAIN_SETVARVALUEFROMSERVER")
    fun setVarValueFromServerHook(varManager: MemorySegment, varInfo: MemorySegment, valuePtr: MemorySegment, extra: Int): MemorySegment {
        synchronized(Bootstrap.lock) {
            try {
                val varId = varInfo.reinterpret(0x10L).deref(OVarInfo.TYPE_PTR, 0x10L).getInt(OVarInfo.VAR_ID)
                val value = valuePtr.reinterpret(0x8).getInt()
                val prev = Bootstrap.client.playerVarDomain.getVar(varId)
                if (prev != value) {
                    val wantsDebug = UIState.varpDebugEnabled.value
                    if (wantsDebug) {
                        UIState.addVarTableEntry("varp", varId, prev, value)
                    }
                    ScriptExecutor.pushEvent(Varp(varId, prev, value))
                    val varBits = VarBitType.baseVarMap[VarDomain.PLAYER]?.get(varId)
                    varBits?.forEach { bit ->
                        val vbPrev = bit.getValue(prev)
                        val vbValue = bit.getValue(value)
                        if (vbPrev != vbValue) {
                            ScriptExecutor.pushEvent(Varpbit(bit.id, vbPrev, vbValue))
                            if (wantsDebug) {
                                UIState.addVarTableEntry("varpbit", bit.id, vbPrev, vbValue)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        return HookManager.trampoline(FROM_SERVER_HOOK)
            .invokeExact(varManager, varInfo, valuePtr, extra) as MemorySegment
    }

    @JvmStatic
    @Hook("PLAYERVARDOMAIN_SETVARVALUE")
    fun setVarValueHook(varManager: MemorySegment, varInfo: MemorySegment, valuePtr: MemorySegment, extra: Int): MemorySegment {
        synchronized(Bootstrap.lock) {
            try {
                val varId = varInfo.reinterpret(0x10L).deref(OVarInfo.TYPE_PTR, 0x10L).getInt(OVarInfo.VAR_ID)
                val value = valuePtr.reinterpret(0x8).getInt()
                val prev = Bootstrap.client.playerVarDomain.getVar(varId)
                if (prev != value) {
                    val debugEnabled = UIState.varpDebugEnabled.value
                    if (debugEnabled) {
                        UIState.addVarTableEntry("varp", varId, prev, value)
                    }
                    ScriptExecutor.pushEvent(Varp(varId, prev, value))
                    val varBits = VarBitType.baseVarMap[VarDomain.PLAYER]?.get(varId)
                    varBits?.forEach { bit ->
                        val vbPrev = bit.getValue(prev)
                        val vbValue = bit.getValue(value)
                        if (vbPrev != vbValue) {
                            ScriptExecutor.pushEvent(Varpbit(bit.id, vbPrev, vbValue))
                            if (debugEnabled) {
                                UIState.addVarTableEntry("varpbit", bit.id, vbPrev, vbValue)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        return HookManager.trampoline(SET_HOOK)
            .invokeExact(varManager, varInfo, valuePtr, extra) as MemorySegment
    }
}
