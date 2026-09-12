package com.projectx.script.impl.pineapple

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*

private val trapNames = setOf("Tortle trap", "Lay Tortle trap", "Check Tortle trap")
private val crossOffsets = listOf(
    0 to 0,
    0 to 1,
    0 to -1,
    1 to 0,
    -1 to 0
)

@ScriptDescription(
    name = "Tortle Hunter",
    version = "1.0.0",
    author = "Pineapple",
    description = "Open area loot before starting the script, and have Tortle traps in your inventory.",
)
class TortleHunter : Script() {
    var startTile: Tile = Tile.EMPTY

    override fun onStart() {
        startTile = localPlayer.tile
    }

    override suspend fun loop() {
        if(!areaLootOpen && !groundItems.isEmpty()){
            openAreaLoot()
        }
        if (areaLootOpen && !areaLoot.isEmpty) {
            IFSlot(1622, 22, -1).click(1)
            return delay(2500, 5000)
        }

        if (localPlayer.isAniMoving) return delay(850, 1000)
        groundItems.forEach { println(it.name)
            println(it.groundOps)
        }

        val maxTraps = getMaxTraps(getCurrentLevel(Skill.HUNTER))
        val traps = getAllObjectsWithinRange(6).filter { trapNames.contains(it.name()) }
        if (traps.size < maxTraps) {
            val occupiedTiles = traps.filter { trapNames.contains(it.name()) }.map { it.tile }.toSet()
            val availableTile = crossOffsets.map { (x, y) -> startTile.transform(x, y) }.firstOrNull { it !in occupiedTiles } ?: return
            return placeTrap(availableTile)
        }

        if (interactClosestObject("Tortle trap", "Check")) {
            delay(1200)
            waitUntilNotAniMoving()
            placeTrap(localPlayer.tile)
            return
        }
    }

    suspend fun placeTrap(tile: Tile) {
        if (localPlayer.tile != tile) {
            walkTo(tile, false)
            waitUntilNotAniMoving()
        }
        delay(1200)
        inventory.clickItem("Tortle trap", "Lay")
        delay(1200)
        waitUntilNotAniMoving()
    }

    private fun getMaxTraps(level: Int): Int = when {
        level < 30 -> 2
        level < 60 -> 3
        level < 80 -> 4
        else -> 5
    }
}
