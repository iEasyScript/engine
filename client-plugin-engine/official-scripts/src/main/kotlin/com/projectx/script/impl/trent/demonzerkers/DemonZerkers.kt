package com.projectx.script.impl.trent.demonzerkers

import world.gregs.voidps.type.Tile
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.equipment
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.script.impl.trent.CombatRotation

private val safeSpot = Tile.of(3344, 3881, 0)
private val lureSpot = Tile.of(3331, 3863, 0)

@ScriptDescription(
    name = "Demon Zerkers",
    version = "1.0.0",
    author = "Trent",
    description = "Safespots demon zerkers in the wilderness."
)
class DemonZerkers : Script() {
    override fun onStart() {
        addParallelScript(CombatRotation())
    }

    override suspend fun loop() {
        if (safeSpot.getDistance(localPlayer.tile) > 100) {
            stop()
            return
        }
        var demon = findClosestNPC { it.name == "Greater demon berserker" && it.interactingWith(localPlayer) }
        if (demon != null) {
            if (localPlayer.tile != safeSpot) {
                walkTo(safeSpot, false)
                delayUntil(2000) { localPlayer.isAniMoving }
                delayUntil(10000) { !localPlayer.isAniMoving }
                return
            }
            if (!localPlayer.isInteracting && demon.tile.getDistance(localPlayer.tile) <= 7 && demon.interact("Attack"))
                delayUntil(5000) { localPlayer.isInteracting }
            return
        }

        demon = findClosestNPC("Greater demon berserker")
        if (demon == null) {
            walkTo(lureSpot.randomize(1), false)
            delayUntil(2000) { localPlayer.isAniMoving }
            delayUntil(10000) { !localPlayer.isAniMoving }
            return
        } else if (demon.interact("Attack"))
            delayUntil(6000) { demon.interactingWith(localPlayer) }
    }
}

class ManageScrim : Script() {
    override suspend fun loop() {
        equipment.getItem("Scrimshaw of sacrifice") ?: return
        findClosestNPC { it.name == "Greater demon berserker" && it.interactingWith(localPlayer) } ?: return
    }
}