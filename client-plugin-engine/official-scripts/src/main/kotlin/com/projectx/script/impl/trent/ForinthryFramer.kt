package com.projectx.script.impl.trent

import org.projectx.core.game.skill.Skill
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.continueMakeX
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.makeXOpen
import com.projectx.util.gaussian

@ScriptDescription(
    name = "Forinthry Framer",
    version = "1.0.0",
    author = "Trent",
    description = "Makes frames in fort forinthry"
)
class ForinthryFramer : StateMachineScript<ForinthryFramer>() {
    override fun getStartState(): State<ForinthryFramer> {
        if (findClosestObject("Optimal Construction hotspot") != null) return BuildHotspot
        resource = inventory.firstOrNull()?.name ?: error("Please fill inventory with the resource you are processing.")
        return Craft()
    }
}

private var resource = ""

class Craft: State<ForinthryFramer>() {
    override suspend fun ForinthryFramer.checkNext() = if (!inventory.hasItem(resource)) Bank else null

    override suspend fun ForinthryFramer.stateLoop() {
        if (!makeXOpen) {
            if (interactClosestObject(if (resource.endsWith(" plank") || resource.endsWith(" logs") || resource == "Logs") "Process planks" else if (resource == "Limestone brick") "Cut stone" else "Construct frames"))
                delayUntil(10000) { makeXOpen }
            return
        }
        continueMakeX()
        waitForXPDrop(Skill.CONSTRUCTION)
        delayUntil(300000) { !inventory.hasItem(resource) }
    }
}

object Bank: State<ForinthryFramer>() {
    override suspend fun ForinthryFramer.checkNext() = if (inventory.hasItem(resource)) Craft() else null

    override suspend fun ForinthryFramer.stateLoop() {
        if (!inventory.hasItem(resource) && interactClosestObject("Load Last Preset from"))
            delayUntil { inventory.hasItem(resource) }
    }
}

object BuildHotspot: State<ForinthryFramer>() {
    override suspend fun ForinthryFramer.checkNext() = null

    override suspend fun ForinthryFramer.stateLoop() {
        if (localPlayer.isMoving) return
        val hotspot = findClosestObject("Optimal Construction hotspot")
        if (hotspot != null && hotspot.interact("Build"))
            delayUntil(gaussian(122030L, 10000L)) { hotspot.exists && hotspot.name() != "Optimal Construction hotspot" }
        delay(820, 300)
    }
}