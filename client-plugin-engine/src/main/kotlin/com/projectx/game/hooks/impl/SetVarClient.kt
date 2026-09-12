package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.getInt
import com.projectx.game.nxt.OFunctions
import com.projectx.game.nxt.OVarInfo
import com.projectx.script.ScriptExecutor
import com.projectx.script.event.impl.Varc
import com.projectx.script.event.impl.Varcbit
import com.projectx.ui.UIState
import world.gregs.voidps.cache.type.data.VarBitType
import world.gregs.voidps.cache.type.data.VarDomain
import java.lang.foreign.MemorySegment

object SetVarClient {
    @JvmStatic
    @Hook("CLIENTVARDOMAIN_SETVARVALUE")
    fun setVarClientHook(varManager: MemorySegment, varInfo: MemorySegment, valuePtr: MemorySegment): MemorySegment {
        synchronized (Bootstrap.lock) {
            try {
                val varId = varInfo.reinterpret(0x10L).deref(OVarInfo.TYPE_PTR, 0x10L).getInt(OVarInfo.VAR_ID)
                val value = valuePtr.reinterpret(0x8).getInt()
                val prev = Bootstrap.client.clientVarDomain.getVar(varId)
                if (prev != value) {
                    val wantsDebug = UIState.varcDebugEnabled.value
                    if (wantsDebug) {
                        UIState.addVarTableEntry("varc", varId, prev, value)
                    }
                    ScriptExecutor.pushEvent(Varc(varId, prev, value))
                    val varBits = VarBitType.baseVarMap[VarDomain.CLIENT]?.get(varId)
                    varBits?.forEach { bit ->
                        val vbPrev = bit.getValue(prev)
                        val vbValue = bit.getValue(value)
                        if (vbPrev != vbValue) {
                            ScriptExecutor.pushEvent(Varcbit(bit.id, vbPrev, vbValue))
                            if (wantsDebug) {
                                UIState.addVarTableEntry("varcbit", bit.id, vbPrev, vbValue)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        return HookManager.trampoline(::setVarClientHook.name).invokeExact(varManager, varInfo, valuePtr) as MemorySegment
    }
}
