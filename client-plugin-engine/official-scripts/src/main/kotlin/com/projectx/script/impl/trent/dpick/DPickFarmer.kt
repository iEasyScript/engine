package com.projectx.script.impl.trent.dpick

import com.projectx.pathfinder.hasLineOfSight
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.util.random

@ScriptDescription(
    name = "D Pick Farmer",
    version = "1.0.0",
    author = "Trent",
    description = "Farms d picks"
)
class DPickFarmer : StateMachineScript<DPickFarmer>() {
    override fun getStartState() = Attack
}

object Attack: State<DPickFarmer>() {
    private var lastLootTime = 0L

    override suspend fun DPickFarmer.checkNext() = if (healthPercent < 20.0) Heal else null

    override suspend fun DPickFarmer.stateLoop() {
        if (areaLootOpen && !areaLoot.isEmpty) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastLootTime >= random(10000, 15000)) {
                lootCustomAllAreaLoot()
                lastLootTime = currentTime
                delay(1600, 1592)
            }
            val valuableNames = setOf("Dragon pickaxe", "Hand cannon", "Mithril stone spirit")
            val valuable = areaLoot.find { valuableNames.contains(it.name) }
            if (valuable?.click(1) == true)
                delay(1500, 1000)
        }

        val dpick = groundItems.firstOrNull { it.name == "Dragon pickaxe" || it.name == "Hand cannon" }
        if (dpick != null) {
            if (dpick.interact("Take"))
                delay(8500, 3000)
            return
        }

        val closestTarget = allNpcsWithinRange(6)
            .filter { it.name.contains("chaos", ignoreCase = true) && it.hasOption("Attack") && it.currentHealth > 0 && hasLineOfSight(localPlayer.tile, 1, it.tile, 1) }
            .minByOrNull { it.tile.getDistance(localPlayer.tile) }

        if (closestTarget?.interact("Attack") == true)
            delay(1368, 3592)
    }
}

object Heal: State<DPickFarmer>() {
    override suspend fun DPickFarmer.checkNext() = if (healthPercent > 30.0) Attack else null
    override suspend fun DPickFarmer.stateLoop() = waitThenDelayUntil(1200, 20000) { healthPercent > 30.0 }
}