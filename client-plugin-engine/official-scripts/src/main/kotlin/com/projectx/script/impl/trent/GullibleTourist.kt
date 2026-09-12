package com.projectx.script.impl.trent

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.healthCurrent
import com.projectx.script.api.interactClosestNPC
import com.projectx.script.api.inventory

@ScriptDescription(
    name = "Gullible Tourist",
    version = "1.0.0",
    author = "Trent",
    description = "Pickpockets master farmer in Draynor"
)
class GullibleTourist : Script() {
    override suspend fun loop() {
        if (healthCurrent <= 60 || inventory.isFull)
            return
        if (interactClosestNPC(24432, "Pickpocket"))
            delay(422, 859)
    }
}