package com.projectx.script.impl.trent.devoutyakaltar

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*

private const val YAK = "Devout Yak"
private val ALTAR = Regex(".+ altar", RegexOption.IGNORE_CASE)

private const val TIMEOUT_MILLIS = 2000L

@ScriptDescription(
    name = "Devout Yak Altar",
    version = "1.0.0",
    author = "Trent",
    description = "Interacts with the Devout Yak until full, then uses the altar"
)
class DevoutYakAltar : Script() {
    override suspend fun loop() {
        if (inventory.isFull) useAltar() else workYak()
    }

    private suspend fun useAltar() {
        val altar = findClosestObject { ALTAR.matches(it.name()) } ?: return delay(300, 150)
        if (!altar.interact("Use")) return delay(300, 150)
        delayUntil(TIMEOUT_MILLIS) { !inventory.isFull }
    }

    private suspend fun workYak() {
        if (!interactClosestNPC(YAK, "Interact")) return delay(300, 150)
        delayUntil(TIMEOUT_MILLIS) { inventory.isFull }
    }
}
