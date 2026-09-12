package com.projectx.script.impl.trent.bonfire

import org.projectx.core.game.skill.Skill
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory

@ScriptDescription(
    name = "Bonfire",
    version = "1.0.0",
    author = "Trent",
    description = "Burns logs on the nearest bonfire, restocking from the last bank preset",
    category = ScriptCategory.FIREMAKING,
)
class Bonfire : StateMachineScript<Bonfire>() {
    override fun getStartState() = Burn
}

/** Burnable logs are all "<type> logs"; the leading space keeps the match off unrelated item names. */
private val LOGS = Regex(".*\\slogs.*", RegexOption.IGNORE_CASE)

private const val PRESET_TIMEOUT = 5_000L
private const val BURN_TIMEOUT = 240_000L

object Burn : State<Bonfire>() {

    override suspend fun Bonfire.checkNext() = if (inventory.hasItem(LOGS)) null else Bank

    override suspend fun Bonfire.stateLoop() {
        if (!interactClosestObject("Add logs to")) return
        // Confirm the burn actually started, then sit idle until the inventory is empty of logs.
        waitForXPDrop(Skill.FIREMAKING)
        delayUntil(BURN_TIMEOUT) { !inventory.hasItem(LOGS) }
    }
}

object Bank : State<Bonfire>() {

    override suspend fun Bonfire.checkNext() = if (inventory.hasItem(LOGS)) Burn else null

    override suspend fun Bonfire.stateLoop() {
        if (interactClosestObject("Load Last Preset from")) {
            delayUntil(PRESET_TIMEOUT) { inventory.hasItem(LOGS) }
        }
    }
}
