package com.projectx.script.impl.trent

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.checkPorter

@ScriptDescription(
    name = "Equip Porters",
    version = "1.0.0",
    author = "Trent",
    description = "Equips porters if they run out."
)
class EquipPorters : Script() {
    override suspend fun loop() {
        checkPorter()
    }
}