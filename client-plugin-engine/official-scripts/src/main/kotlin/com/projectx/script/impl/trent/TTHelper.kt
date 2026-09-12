package com.projectx.script.impl.trent

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.interactClosestObject

@ScriptDescription(
    name = "Temple Trekking Helper",
    version = "1.0.0",
    author = "Trent",
    description = "Helps with temple trekking"
)
class TTHelper : Script() {
    override suspend fun loop() {
        if (interactClosestObject(52992, "Stand-on"))
            delay(3000, 1000)
    }
}