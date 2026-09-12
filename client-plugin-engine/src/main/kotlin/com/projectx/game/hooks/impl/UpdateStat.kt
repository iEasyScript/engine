package com.projectx.game.hooks.impl

import org.projectx.core.game.skill.Skill
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.hooks.Hook
import com.projectx.game.hooks.HookManager
import com.projectx.game.nxt.OFunctions
import com.projectx.script.ScriptExecutor
import com.projectx.script.api.refreshLastXpDrop
import com.projectx.script.api.timeLoggedInAt
import com.projectx.script.event.impl.XPDrop
import com.projectx.ui.UIState
import java.lang.foreign.MemorySegment

object UpdateStat {
    @JvmStatic
    @Hook("STATTABLE_UPDATESTAT")
    fun updateStatHook(clientPtr: MemorySegment, packetPtr: MemorySegment): MemorySegment {
        synchronized (Bootstrap.lock) {
            val prevXp = Bootstrap.client.skills.map { it.xp }.toMutableList()
            val retVal = HookManager.trampoline(::updateStatHook.name).invokeExact(clientPtr, packetPtr) as MemorySegment
            if ((System.currentTimeMillis() - timeLoggedInAt) > 15000L) {
                Bootstrap.client.skills.forEachIndexed { index, skill ->
                    val newXP = skill.xp
                    val oldXP = prevXp[index]
                    if (newXP > oldXP) {
                        val event = XPDrop(Skill.entries[index], newXP - oldXP)
                        refreshLastXpDrop()
                        ScriptExecutor.pushEvent(event)
                        UIState.updateXpTable(event.skill, event.gainedXp)
                    }
                }
            }
            return retVal
        }
    }
}