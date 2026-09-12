package com.projectx.script.impl.trent.aiofish

import world.gregs.voidps.type.Tile
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*

@ScriptDescription(
    name = "AIO Fish",
    version = "1.0.0",
    author = "Trent",
    description = "Fishes in popular fishing locations."
)
class AIOFish : StateMachineScript<AIOFish>() {
    override fun getStartState() = UnknownLocationState
}

object UnknownLocationState : State<AIOFish>() {
    override suspend fun AIOFish.checkNext() = detectFishLocation()
    override suspend fun AIOFish.stateLoop() { }
}

fun detectFishLocation(): State<AIOFish>? {
    return when {
        localPlayer.tile.withinDistance(Tile.of(3215, 2628, 0), 20) -> MenaphosPortDistrict
        localPlayer.tile.withinDistance(Tile.of(3217, 3257, 0), 40) -> LumbridgeFlyFishing
        localPlayer.tile.withinDistance(Tile.of(2098, 7085, 0), 15) -> SwarmFishing
        localPlayer.tile.withinDistance(Tile.of(2282, 3417, 2), 50) -> WaterfallFishing
        else -> null
    }
}

object LumbridgeFlyFishing : State<AIOFish>() {
    override suspend fun AIOFish.checkNext() = null

    override suspend fun AIOFish.stateLoop() {
        if (inventory.isFull) {
            if (interactClosestReachableObject("Bank chest", "Load Last Preset from"))
                waitThenDelayUntil(1200, 25000) { !inventory.isFull }
            else if (walkTo(Tile.of(3217, 3257, 0).randomize(3), true))
                delay(7283, 2000)
            return
        }
        if (interactClosestNPC("Fishing spot", "Lure"))
            waitThenDelayUntil(1200, 180000) { !localPlayer.isAniMoving }
        else if (walkTo(Tile.of(3240, 3252, 0).randomize(3), true))
            delay(7283, 2000)
    }
}

object MenaphosPortDistrict : State<AIOFish>() {
    override suspend fun AIOFish.checkNext() = null

    override suspend fun AIOFish.stateLoop() {
        if (inventory.isFull) {
            if (interactClosestObject("Deposit all fish"))
                waitThenDelayUntil(1200, 25000) { !inventory.isFull }
            return
        }
        if (interactClosestNPC("Fishing spot", "Bait")) {
            waitThenDelayUntil(1200, 50000) { localPlayer.isAnimating }
            delay(250, 530)
            waitThenDelayUntil(1200, 180000) { !localPlayer.isAniMoving }
        }
    }
}

object SwarmFishing : State<AIOFish>() {
    override suspend fun AIOFish.checkNext() = null

    override suspend fun AIOFish.stateLoop() {
        if (inventory.isFull) {
            if (interactClosestObject("Deposit all fish"))
                waitThenDelayUntil(1200, 25000) { !inventory.isFull }
            return
        }
        if (interactClosestNPC("Swarm", "Net")) {
            waitThenDelayUntil(1200, 50000) { localPlayer.isAnimating }
            delay(250, 530)
            waitThenDelayUntil(1200, 180000) { !localPlayer.isAniMoving }
        }
    }
}

object WaterfallFishing : State<AIOFish>() {
    override suspend fun AIOFish.checkNext() = null

    override suspend fun AIOFish.stateLoop() {
        if (interactClosestNPC("Fishing spot", "Catch")) {
            waitThenDelayUntil(1200, 50000) { localPlayer.isAnimating }
            delay(250, 530)
            waitThenDelayUntil(1200) { !localPlayer.isAniMoving }
        }
    }
}