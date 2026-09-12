package com.projectx.script.impl.trent

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.hasActiveMakeXProgress
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory

@ScriptDescription(
    name = "Disassemble Inventory",
    version = "1.0.0",
    author = "Trent",
    description = "Disassembles your entire inventory."
)
class DisassembleInventory : Script() {
    private val confirm = IFSlot(847, 22)
    override suspend fun loop() {
        if (hasActiveMakeXProgress) return
        if (inventory.firstOrNull()?.disassemble() == true) {
            delayUntil(3000) { interfaces.isOpen(847) }
            if (interfaces.isOpen(847) && confirm.dialogueContinue())
                delayUntil(3000) { hasActiveMakeXProgress }
            else
                delayUntil(3000) { hasActiveMakeXProgress }
        }
    }
}