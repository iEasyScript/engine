package com.projectx.script.impl.devin

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.localPlayer
import com.projectx.script.api.varps
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat

@ScriptDescription(
    name = "Whirligiggers",
    version = "1.0.0",
    author = "Devin",
    description = "Whirligig hunter.",
    visible = false
)
class Whirligiggers : Script() {
    private val debug = true

    private val stackSizeUnlock = 50818

    private val activeCrocodile = 50811
    private val scarabStacks = 50812
    private val chatMessages = listOf(
        "Your crocodile successfully catches the scarab.",
        "Your crocodile successfully stacks the scarab.",
        "Your crocodile fails to catch the scarab.",
        "Your crocodile fails to stack the scarab.",
    )

    override suspend fun loop() {
        if (debug) {
            println("varbit active: " + varps.getVarBit(activeCrocodile))
            println("varbit stacks: " + varps.getVarBit(scarabStacks))
        }

        var stakcSize = 3
        if(varps.getVarBit(stackSizeUnlock) == 1) {
            stakcSize = 5
        }

        if (varps.getVarBit(activeCrocodile) == 0) {
            findClosestNPC { it.name.contains("Plain whirligig") }?.interact("Catch")
            delayUntil(15000) { varps.getVarBit(activeCrocodile) == 1 }
        }
        else if (varps.getVarBit(scarabStacks) < stakcSize) {
            findClosestNPC { it.name.contains("whirligig") && !it.name().contains("Plain") }?.interact("Catch")
        }
        delayUntil(15000) { !localPlayer.isAnimating }
        return delay(1200, 1800)
    }

    override fun onEvent(event: Event) {
        if (event is Chat && chatMessages.any { event.message.contains(it, ignoreCase = true) } && debug) {
            println(event.message)
        }
    }
}