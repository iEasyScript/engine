package com.projectx.script.impl.trent.ashcollector

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.areaLoot
import com.projectx.script.api.areaLootOpen
import com.projectx.script.api.groundItems
import com.projectx.script.api.interactClosestObject
import com.projectx.script.api.keyDown
import com.projectx.script.api.localPlayer
import com.projectx.script.api.lootAllAreaLoot
import com.projectx.script.api.openAreaLoot

@ScriptDescription(
    name = "Ash Collector",
    version = "1.0.0",
    author = "Trent",
    description = "Picks the closest glowing fungus and area-loots the resulting ashes while holding 0."
)
class AshCollector : Script() {
    override fun onStart() {
        keyDown('0')
    }

    override suspend fun loop() {
        if (areaLootOpen) {
            if (!areaLoot.isEmpty) {
                lootAllAreaLoot()
                delay(112, 228)
            }
        } else if (hasNearbyGroundItems()) {
            openAreaLoot()
            return
        }

        if (interactClosestObject("Glowing fungus", "Pick", range = 1))
            delay(412, 328)
    }

    private fun hasNearbyGroundItems(): Boolean = try {
        groundItems.any { it.tile.withinDistance(localPlayer.tile, 6) }
    } catch (_: Exception) {
        false
    }
}
