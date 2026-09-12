package com.projectx.game.hooks.impl

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.HookManager
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.util.componentIdFromHash
import com.projectx.util.interfaceIdFromHash
import world.gregs.voidps.gameval.Gameval
import java.lang.foreign.MemorySegment

object IfButtonX {
    @JvmStatic
    fun IFButtonXHook(interfaceManagerPtr: MemorySegment, ifComponentSharedPtr: MemorySegment, ifComponentHash: Int, slotId: Int, opNum: Int, targetString: MemorySegment, doActionSourced: Boolean) {
        synchronized(Bootstrap.lock) {
            if (!doActionSourced) {
                println("[IFButtonX]: IFComponentSharedPtr: ${ifComponentSharedPtr.address().toString(16)}")
                val str = targetString.getOrNull?.reinterpret(0x200)?.getString(0x0)
                println("\tOpNum: $opNum IFSlot(${Gameval.interfaceLabel(interfaceIdFromHash(ifComponentHash))}, ${Gameval.componentLabel(interfaceIdFromHash(ifComponentHash), componentIdFromHash(ifComponentHash))}, $slotId) targetStr: $str")
            }
            HookManager.trampoline(::IFButtonXHook.name).invoke(interfaceManagerPtr, ifComponentSharedPtr, ifComponentHash, slotId, opNum, targetString, doActionSourced)
        }
    }
}