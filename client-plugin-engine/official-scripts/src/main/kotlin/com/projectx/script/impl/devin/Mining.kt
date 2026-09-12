package com.projectx.script.impl.devin

import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.interactClosestReachableObject
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import java.lang.System.currentTimeMillis
import kotlin.random.Random

@ScriptDescription(
    name = "Mining",
    version = "1.0.0",
    author = "Devin",
    description = "Super basic mining script that is focused on mining Banite near mage bank.",
    visible = false
)
class Mining : StateMachineScript<Mining>() {
    override fun getStartState() = GatherOres
}

private val rock = "Banite rock"
private val oreIds = intArrayOf(21778)

object GatherOres: State<Mining>() {
    private var lastMineTime = currentTimeMillis()
    private var randomDelay = Random.nextLong(2400, 20000)

    override suspend fun Mining.checkNext() = if (inventory.isFull) Deposit else null

    override suspend fun Mining.stateLoop() {
        val currentTime = currentTimeMillis()

        if (!localPlayer.isAniMoving || (currentTime - lastMineTime >= randomDelay)) {
            randomDelay = Random.nextLong(2400, 20000)
            lastMineTime = currentTime
            interactClosestReachableObject(rock, "Mine")
        }
        delay(3500, 5200)
    }
}

object Deposit: State<Mining>() {
    override suspend fun Mining.checkNext() = if (!inventory.hasItem(*oreIds)) GatherOres else null

    override suspend fun Mining.stateLoop() {
        if (interactClosestReachableObject("Deposit-all (into metal bank)", 20))
            waitThenDelayUntil(1200, 60000) { !inventory.hasItem(*oreIds) }
    }
}