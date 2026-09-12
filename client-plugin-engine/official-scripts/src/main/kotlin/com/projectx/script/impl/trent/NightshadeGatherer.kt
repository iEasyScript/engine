package com.projectx.script.impl.trent

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.interactClosestObject

@ScriptDescription(
    name = "Nightshade Gatherer",
    version = "1.0.0",
    author = "Trent",
    description = "Gathers nightshade in skavid caves"
)
class NightshadeGatherer : Script() {
    override fun onStart() = addParallelScript(EquipPorters())

    override suspend fun loop() {
        if (interactClosestObject("Cave nightshade", "Pick")) {
            waitForXPDrop()
            delay(210, 85)
        } else
            delay(310, 200)
    }
}