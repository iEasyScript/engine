package com.projectx.script.impl.trent.croesusgather

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.keyDown
import com.projectx.script.api.localPlayer
import com.projectx.script.api.timeSinceLastAnim
import com.projectx.script.event.Event

private val NORMAL_NPC = setOf(28414, 28417, 28420, 28423)
private val ENRICHED_NPC = setOf(28415, 28418, 28421, 28424)

private val NORMAL_OBJ = setOf(121747, 121750, 121753, 121756)
private val ENRICHED_OBJ = setOf(121748, 121751, 121754, 121757)

@ScriptDescription(
    name = "Croesus Gather",
    version = "1.0.0",
    author = "Trent",
    description = "Powergathers at croesus"
)
class CroesusGather : Script() {
    override fun onStart() {
        println("Started Croesus Gather. Pressing drop keybinds down.")
        keyDown('1')
        keyDown('2')
    }

    override suspend fun loop() {
        if (localPlayer.isInteracting || timeSinceLastAnim < 3000)
            return
        println("time: $timeSinceLastAnim")
        val target = findClosestObject { ENRICHED_OBJ.contains(it.id) } ?: findClosestObject { NORMAL_OBJ.contains(it.id) } ?: return
        if (target.interact("Gather"))
            waitThenDelayUntil(1600, 20000) { !localPlayer.isAniMoving || timeSinceLastAnim < 3000 }
        else
            delay(520, 200)
    }

    override fun onEvent(event: Event) {
    }
}