package com.projectx.script.impl.qb.Temporary

import com.projectx.game.chat.MessageType
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat

private val flax = Regex(".*flax$", RegexOption.IGNORE_CASE)
private val bowstring = Regex(".*bowstring$", RegexOption.IGNORE_CASE)

@ScriptDescription(
    name = "Flax Stringer",
    version = "1.0.0",
    author = "Billy",
    description = "Picks flax and strings it into bowstrings at starting area."
)
class FlaxString : StateMachineScript<FlaxString>() {
    override fun getStartState() = PickFlax
}

object PickFlax: State<FlaxString>() {
    override suspend fun FlaxString.checkNext() = when {
        inventory.isFull -> SpinFlax
        else -> null
    }

    override suspend fun FlaxString.stateLoop() {
        if(localPlayer.isAnimating) return
        if(localPlayer.isMoving) return
        interactClosestObject("Flax","Pick", 200)
        delayUntil(3000) { inventory.hasItem(flax) }
    }
}

object SpinFlax: State<FlaxString>() {
    override suspend fun FlaxString.checkNext() = when {
        !inventory.hasItem(flax) && inventory.hasItem(bowstring) -> BankStrings
        !inventory.hasItem(flax) && !inventory.hasItem(bowstring) -> PickFlax
        else -> null
    }

    override suspend fun FlaxString.stateLoop() {
        if(localPlayer.isAnimating) return
        if(hasActiveMakeXProgress) return
        if (!makeXOpen) {
            interactClosestObject("Spinning wheel","Spin", 100)
            delayUntil(8000) { makeXOpen }
            return
        }
        continueMakeX()
        delayUntil(2500) { !inventory.hasItem(flax) }
    }
}

object BankStrings: State<FlaxString>() {
    override suspend fun FlaxString.checkNext() = when {
        !inventory.hasItem(bowstring) -> PickFlax
        else -> null
    }

    override suspend fun FlaxString.stateLoop() {
        if (!bankOpen) {
            interactClosestObject("Bank", 100)
            delayUntil(8000) { bankOpen }
            return
        }

        depositBankItem("Bowstring", 0)
        delayUntil(2500) { !inventory.hasItem(bowstring) }
    }

    override fun FlaxString.onStateEvent(event: Event) {
        if (event !is Chat) return
        if (event.messageType == MessageType.FILTERABLE && event.message.contains("Item could not be found"))
            stop()
    }
}
