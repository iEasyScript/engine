package com.projectx.game.interfaces

import com.projectx.game.nxt.DoActionOpcode
import com.projectx.script.api.interfaces
import com.projectx.util.hashFromInterface

class IFSlot(val interfaceId: Int, val componentId: Int, val slotId: Int = -1) {
    val hash = hashFromInterface(interfaceId, componentId)

    @JvmOverloads
    fun click(option: Int = 1): Boolean {
        if (interfaceId < 0 || componentId < 0 || interfaces.getComponent(interfaceId, componentId) == null) return false
        val op = if (option >= 6) DoActionOpcode.COMPONENT_SIXPLUS else (DoActionOpcode.COMPONENT)
        op.fire(option, slotId, hash)
        return true
    }

    @JvmOverloads
    fun dialogueContinue(param2: Int =-1): Boolean {
        if (interfaceId < 0 || componentId < 0 || interfaces.getComponent(interfaceId, componentId) == null) return false
        DoActionOpcode.DIALOGUE.fire(0, param2, hash)
        return true
    }

    fun select(): Boolean {
        if (interfaceId < 0 || componentId < 0 || interfaces.getComponent(interfaceId, componentId) == null) return false
        DoActionOpcode.SELECT_COMPONENT.fire(0, slotId, hash)
        return true
    }

    fun target(): Boolean {
        if (interfaceId < 0 || componentId < 0 || interfaces.getComponent(interfaceId, componentId) == null) return false
        DoActionOpcode.SELECT_COMPONENT_ITEM.fire(0, slotId, hash)
        return true
    }
}