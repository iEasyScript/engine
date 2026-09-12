package com.projectx.script.impl.trent

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer

@ScriptDescription(
    name = "Cleansing Crystals",
    version = "1.0.0",
    author = "Trent",
    description = "Cleanses cleansing crystals"
)
class CleansingCrystals : Script() {
    override suspend fun loop() {
        if (!localPlayer.headbars.isEmpty()) return
        if (inventory.hasItem("Cleansing crystal")) {
            if (interactClosestObject("Cleanse")) {
                delayUntil(15000) { !localPlayer.headbars.isEmpty() }
                delay(2502, 600)
            }
        } else
            stop()
    }
}