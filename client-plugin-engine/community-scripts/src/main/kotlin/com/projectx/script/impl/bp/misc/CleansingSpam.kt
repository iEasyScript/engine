package com.projectx.script.impl.bp.misc

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.MainState
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer

@ScriptDescription(
    name = "Cleansing Crystal Spam",
    version = "1.0.0",
    author = "BP",
    description = "Spams cleansing crystals on corrupted seren stone",
    category = ScriptCategory.PRAYER
)
class CleansingCrystal : StateMachineScript<CleansingCrystal>() {
    override fun getStartState(): State<CleansingCrystal> = Cleanse
}

object Cleanse: State<CleansingCrystal>() {
    var lastTick = Bootstrap.client.clientCycle / 30.0

    override suspend fun CleansingCrystal.checkNext() = null
    override suspend fun CleansingCrystal.stateLoop() {
        if (!inventory.hasItem("Cleansing crystal")) return delay(1000)

        if (Bootstrap.client.clientCycle / 30.0 - lastTick > 1) {
            delay(300,300)
            if (interactClosestObject("Cleanse"))
                lastTick = Bootstrap.client.clientCycle / 30.0
        }
    }
}
