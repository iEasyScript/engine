package com.projectx.script.impl.trent.presetbankstandmakex

import com.projectx.game.chat.MessageType
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat

@ScriptDescription(
    name = "Preset Bankstand Make-X",
    version = "1.0.0",
    author = "Trent",
    description = "Makes X via bankstanding and presets for whatever using a defined key on actionbar"
)
class PresetBankStandMakeX : StateMachineScript<PresetBankStandMakeX>() {
    override fun getStartState() = Make()
}

class Make: State<PresetBankStandMakeX>() {
    var making = false

    override suspend fun PresetBankStandMakeX.checkNext() = if (making && !hasActiveMakeXProgress) Bank() else null

    override suspend fun PresetBankStandMakeX.stateLoop() {
        if (hasActiveMakeXProgress) return
        if (!makeXOpen) {
            clickKey('1')
            delayUntil(4500) { makeXOpen }
            return
        }
        continueMakeX()
        delayUntil { interfaces.isOpen(1251) }
        making = true
    }
}

class Bank: State<PresetBankStandMakeX>() {
    var withdrawn = false

    override suspend fun PresetBankStandMakeX.checkNext() = if (withdrawn) Make() else null

    override suspend fun PresetBankStandMakeX.stateLoop() {
        interactClosestObject("Load Last Preset from")
        delay(1520, 852)
    }

    override fun PresetBankStandMakeX.onStateEvent(event: Event) {
        if (event !is Chat) return
        if (event.messageType == MessageType.FILTERABLE && event.message.contains("Your preset is being withdrawn"))
            withdrawn = true
        if (event.messageType == MessageType.FILTERABLE && event.message.contains("Item could not be found"))
            stop()
    }
}