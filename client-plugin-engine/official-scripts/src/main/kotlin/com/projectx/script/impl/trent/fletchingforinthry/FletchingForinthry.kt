package com.projectx.script.impl.trent.fletchingforinthry

import org.projectx.core.game.skill.Skill
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.util.random

@ScriptDescription(
    name = "Fletching Forinthry",
    version = "1.0.0",
    author = "Trent",
    description = "Fletches using presets in fort forinthry"
)
class FletchingForinthry : StateMachineScript<FletchingForinthry>() {
    override fun getStartState() = Fletch()
}

private val logsOrBowstring = Regex(".*(?:\\slogs|owstring)$")

private val DINARROW = 53038
private val HEADLESS_DINARROW = 53033
private val DIN_PROPELLANT = 53073
private val DIN_SHAFT = 53083
private val DIN_SHARD = 53093

class Fletch: State<FletchingForinthry>() {
    override suspend fun FletchingForinthry.checkNext(): State<FletchingForinthry>? {
        if (inventory.hasItem(DIN_SHAFT) && inventory.hasItem("Fungal bowstring"))
            return Spin()
        if (inventory.hasItem(DINARROW, HEADLESS_DINARROW, DIN_SHAFT, DIN_PROPELLANT, DIN_SHARD)) {
            return null
        } else {
            if (!inventory.hasItem(logsOrBowstring))
                return Bank
        }
        return null
    }

    override suspend fun FletchingForinthry.stateLoop() {
        if (timeSinceLastXpDrop > random(1802, 2359)) {
            if (!makeXOpen) {
                if (interactClosestObject("Fletching workbench", "Use"))
                    delayUntil { makeXOpen }
                return
            }
            continueMakeX()
            waitForXPDrop(Skill.FLETCHING)
        }
    }
}

class Spin: State<FletchingForinthry>() {
    override suspend fun FletchingForinthry.checkNext() = null

    override suspend fun FletchingForinthry.stateLoop() {
        if (inventory.count(DIN_SHAFT) < 5) stop()
        if (timeSinceLastXpDrop > random(1802, 2359)) {
            if (!makeXOpen) {
                if (interactClosestObject("Spinning wheel", "Spin"))
                    delayUntil(2500) { makeXOpen }
                return
            }
            continueMakeX()
            waitForXPDrop(Skill.FLETCHING)
        }
    }
}

object Bank: State<FletchingForinthry>() {
    override suspend fun FletchingForinthry.checkNext() = if (inventory.hasItem(logsOrBowstring)) Fletch() else null

    override suspend fun FletchingForinthry.stateLoop() {
        if (!inventory.hasItem(logsOrBowstring) && interactClosestObject("Load Last Preset from"))
            delayUntil { inventory.hasItem(logsOrBowstring) }
    }
}