package com.projectx.script.impl.qb.Temporary

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.util.random

@ScriptDescription(
    name = "HerbyWerby State Machine",
    version = "1.0.0",
    author = "Converted from BWU",
    description = "Automated HerbyWerby minigame with state machine logic"
)
class HerbyWerby : StateMachineScript<HerbyWerby>() {

    private val herbyEntranceArea = Tile(5611, 2453, 0)
    private val herbyCompletionVarbit = 44351
    private val herbyCompletionValue = 100

    override fun getStartState(): State<HerbyWerby> = CheckCompletionState()

    class CheckCompletionState : State<HerbyWerby>() {
        override suspend fun HerbyWerby.checkNext(): State<HerbyWerby>? {
            return when {
                varps.getVarBit(herbyCompletionVarbit) == herbyCompletionValue -> IdleState()
                isNearRitualFire() -> CollectHerbState()
                isAtEntrance() -> EnterHerbyState()
                else -> TravelToEntranceState()
            }
        }

        override suspend fun HerbyWerby.stateLoop() {
            delay(random(1000, 1500))
        }
    }

    class IdleState : State<HerbyWerby>() {
        override suspend fun HerbyWerby.checkNext(): State<HerbyWerby>? {
            return if (varps.getVarBit(herbyCompletionVarbit) != herbyCompletionValue) {
                CheckCompletionState()
            } else null
        }

        override suspend fun HerbyWerby.stateLoop() {
            println("HerbyWerby completed for this week!")
            delay(random(5000, 10000))
        }
    }

    class TravelToEntranceState : State<HerbyWerby>() {
        override suspend fun HerbyWerby.checkNext(): State<HerbyWerby>? {
            return when {
                isAtEntrance() -> EnterHerbyState()
                isNearRitualFire() -> CollectHerbState()
                else -> null
            }
        }

        override suspend fun HerbyWerby.stateLoop() {
            println("Traveling to HerbyWerby entrance...")

            delay(random(1000, 2000))
        }
    }

    class EnterHerbyState : State<HerbyWerby>() {
        override suspend fun HerbyWerby.checkNext(): State<HerbyWerby>? {
            return when {
                isNearRitualFire() -> CollectHerbState()
                !isAtEntrance() -> TravelToEntranceState()
                else -> null
            }
        }

        override suspend fun HerbyWerby.stateLoop() {
            println("At HerbyWerby entrance, preparing to enter...")

            removeEquippedWeapons()

            val treeRoots = findClosestObject("Tree roots")
            if (treeRoots != null) {
                println("Found tree roots, climbing down...")
                treeRoots.interact("Climb down")

                waitForInterface(1188, 10000)
                delay(random(500, 1000))
                if(interfaces.isOpen(1188))
                {
                    IFSlot(188, 8, -1).click()
                }

                delay(random(300, 4000))
            } else {
                println("Could not find tree roots")
                delay(random(1000, 2000))
            }
        }
    }

    class CollectHerbState : State<HerbyWerby>() {
        override suspend fun HerbyWerby.checkNext(): State<HerbyWerby>? {
            return when {
                varps.getVarBit(herbyCompletionVarbit) == herbyCompletionValue -> IdleState()
                !isNearRitualFire() -> CheckCompletionState()
                else -> null
            }
        }

        override suspend fun HerbyWerby.stateLoop() {
            println("Collecting herbs from green zygomites...")

            findClosestNPCWithOption("Take herb")?.let{
                    zygomite -> if( zygomite.interact("Take herb") ) {
                    waitThenDelayUntil(856) { localPlayer.isAnimating }
                    while(zygomite.exists()) {
                        if (zygomite.spotAnims.any { it.id == 7237 }) {
                            delay(225, 350)
                            if (findClosestNPC { it.hasOption("Take herb") }?.interact("Take herb") == true)
                                delayUntil(30000) { !zygomite.spotAnims.any { it.id == 7237 } || !zygomite.exists() }
                        }
                        delay(150, 100)
                    }

                }

            }
        }
    }

    private fun isAtEntrance(): Boolean {
        return localPlayer.tile.getDistance(herbyEntranceArea) <= 5
    }

    private fun isNearRitualFire(): Boolean {
        return findClosestObject("Ritual fire") != null
    }

    private fun removeEquippedWeapons() {
    }

    private suspend fun waitForInterface(interfaceId: Int, timeout: Long) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            if (interfaces.isOpen(interfaceId)) {
                return
            }
            delay(100)
        }
    }
}