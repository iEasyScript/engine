package com.projectx.script.impl.trent.leathertanner

import com.projectx.game.chat.MessageType
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat

private val hides = Regex(".*hide$", RegexOption.IGNORE_CASE)

@ScriptDescription(
    name = "Leather Tanner",
    version = "1.0.0",
    author = "Trent",
    description = "Tans leather ideally in Priffdinas"
)
class LeatherTanner : StateMachineScript<LeatherTanner>() {
    override fun getStartState() = Tan
}

object Tan: State<LeatherTanner>() {
    override suspend fun LeatherTanner.checkNext() = if (!inventory.hasItem(hides)) Bank else null

    override suspend fun LeatherTanner.stateLoop() {
        if (!makeXOpen) {
            interactClosestNPC("Tan hide")
            delayUntil(8000) { makeXOpen }
            return
        }
        continueMakeX()
        delayUntil(2500) { !inventory.hasItem(hides) }
    }
}

object Bank: State<LeatherTanner>() {
    override suspend fun LeatherTanner.checkNext() = if (inventory.hasItem(hides)) Tan else null

    override suspend fun LeatherTanner.stateLoop() {
        interactClosestObject("Load Last Preset from")
        delayUntil(8000) { inventory.hasItem(hides) }
    }

    override fun LeatherTanner.onStateEvent(event: Event) {
        if (event !is Chat) return
        if (event.messageType == MessageType.FILTERABLE && event.message.contains("Item could not be found"))
            stop()
    }
}