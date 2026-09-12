package com.projectx.script.impl.qb.bosses.barrows

import world.gregs.voidps.type.Tile
import org.projectx.core.game.combat.Ability
import com.projectx.game.interfaces.IFSlot
import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.api.Area.Rectangular
import com.projectx.traversal.Traversal.Companion.traversal
import com.projectx.util.random

@ScriptDescription(name = "Barrows Sisters", version = "1.0.0", author = "QB", description = "Nope")
class BarrowsBrothers : StateMachineScript<BarrowsBrothers>() {
    override fun getStartState(): State<BarrowsBrothers> {
        return Banking()
    }

    var brotherNameArray =
        arrayOf(
            "Linza the Disgraced",
            "Ahrim the Blighted",
            "Akrisae the Doomed",
            "Dharok the Wretched",
            "Guthan the Infested",
            "Karil the Tainted",
            "Torag the Corrupted",
            "Verac the Defiled"
        )

    var cryptWalkStarted = false

    var hiddenTunnel = ""
    var ahrimKilled = false
    var dharockKilled = false
    var guthanKilled = false
    var karilKilled = false
    var toragKilled = false
    var verakKilled = false
    var akrisaeKilled = false
    var linzaKilled = false

    var puzzleSolved = false
    var chestOpened = false
    var chestClaimed = false

    fun updateData() {
        val killData: Int = varps.getVar(1513)
        val tunnelData: Int = varps.getVar(1512)

        ahrimKilled = checkBitState(killData, 0)
        dharockKilled = checkBitState(killData, 1)
        guthanKilled = checkBitState(killData, 2)
        karilKilled = checkBitState(killData, 3)
        toragKilled = checkBitState(killData, 4)
        verakKilled = checkBitState(killData, 5)
        akrisaeKilled = varps.getVarBit(11655) == 1
        linzaKilled = varps.getVarBit(31434) == 1

        puzzleSolved = checkBitState(tunnelData, 31)
        chestOpened = checkBitState(killData, 16)
        chestClaimed = checkBitState(tunnelData, 26)
        hiddenTunnel = getHiddenTunnel(tunnelData)

        if (varps.getVarBit(11610) != 400) akrisaeKilled = true
        if (varps.getVarBit(31410) != 140) linzaKilled = true
    }

    fun getPuzzleSolution(tunnelData: Int) {
        if (interfaces.isOpen(25)) {
            val bit30: Boolean = this.checkBitState(tunnelData, 30)
            val bit29: Boolean = this.checkBitState(tunnelData, 29)
            if (bit30) {
                println("Puzzle Solution: Clicking Right.")
                IFSlot(25, 6, -1).click()
            } else if (bit29) {
                println("Puzzle Solution: Clicking Middle.")
                IFSlot(25, 4, -1).click()
            } else {
                println("Puzzle Solution: Clicking Left.")
                IFSlot(25, 3, -1).click()
            }
        }
    }

    fun allBrothersDead(): Boolean {
        updateData()
        return ahrimKilled &&
                dharockKilled &&
                guthanKilled &&
                karilKilled &&
                toragKilled &&
                verakKilled &&
                akrisaeKilled &&
                linzaKilled
    }

    fun checkBitState(value: Int, bitPosition: Int): Boolean {
        return (value shr bitPosition and 1) == 1
    }

    fun getHiddenTunnel(tunnelData: Int): String {
        return if (checkBitState(tunnelData, 0)) {
            "Ahrim"
        } else if (checkBitState(tunnelData, 1)) {
            "Dharok"
        } else if (checkBitState(tunnelData, 2)) {
            "Guthan"
        } else if (checkBitState(tunnelData, 3)) {
            "Karil"
        } else if (checkBitState(tunnelData, 4)) {
            "Torag"
        } else {
            if (checkBitState(tunnelData, 5)) "Verac" else "Akrisae"
        }
    }

    var StartingRoom: String? = null
    var NEbottomLeft: Tile = Tile.of(3563, 9706, 0)
    var NEtopRight: Tile = Tile.of(3574, 9717, 0)
    var NEROOM: Rectangular? = null
    var NWbottomLeft: Tile? = null
    var NWtopRight: Tile? = null
    var NWROOM: Rectangular? = null
    var SWbottomLeft: Tile? = null
    var SWtopRight: Tile? = null
    var SWROOM: Rectangular? = null
    var SEbottomLeft: Tile? = null
    var SEtopRight: Tile? = null
    var SEROOM: Rectangular? = null
    var CbottomLeft: Tile? = null
    var CtopRight: Tile? = null
    var CROOM: Rectangular? = null
    var NW_N_DOOR: Tile? = null
    var NW_N_DOOR_OPEN: Boolean = false
    var NW_W_DOOR: Tile? = null
    var NW_W_DOOR_OPEN: Boolean = false
    var NW_S_DOOR: Tile? = null
    var NW_S_DOOR_OPEN: Boolean = false
    var NW_E_DOOR: Tile? = null
    var NW_E_DOOR_OPEN: Boolean = false
    var N_W_DOOR: Tile? = null
    var N_W_DOOR_OPEN: Boolean = false
    var N_S_DOOR: Tile? = null
    var N_S_DOOR_OPEN: Boolean = false
    var N_E_DOOR: Tile? = null
    var N_E_DOOR_OPEN: Boolean = false
    var NE_N_DOOR: Tile? = null
    var NE_N_DOOR_OPEN: Boolean = false
    var NE_W_DOOR: Tile? = null
    var NE_W_DOOR_OPEN: Boolean = false
    var NE_S_DOOR: Tile? = null
    var NE_S_DOOR_OPEN: Boolean = false
    var NE_E_DOOR: Tile? = null
    var NE_E_DOOR_OPEN: Boolean = false
    var W_N_DOOR: Tile? = null
    var W_N_DOOR_OPEN: Boolean = false
    var W_S_DOOR: Tile? = null
    var W_S_DOOR_OPEN: Boolean = false
    var W_E_DOOR: Tile? = null
    var W_E_DOOR_OPEN: Boolean = false
    var C_N_DOOR: Tile? = null
    var C_N_DOOR_OPEN: Boolean = false
    var C_W_DOOR: Tile? = null
    var C_W_DOOR_OPEN: Boolean = false
    var C_S_DOOR: Tile? = null
    var C_S_DOOR_OPEN: Boolean = false
    var C_E_DOOR: Tile? = null
    var C_E_DOOR_OPEN: Boolean = false
    var E_N_DOOR: Tile? = null
    var E_N_DOOR_OPEN: Boolean = false
    var E_W_DOOR: Tile? = null
    var E_W_DOOR_OPEN: Boolean = false
    var E_S_DOOR: Tile? = null
    var E_S_DOOR_OPEN: Boolean = false
    var SW_N_DOOR: Tile? = null
    var SW_N_DOOR_OPEN: Boolean = false
    var SW_W_DOOR: Tile? = null
    var SW_W_DOOR_OPEN: Boolean = false
    var SW_S_DOOR: Tile? = null
    var SW_S_DOOR_OPEN: Boolean = false
    var SW_E_DOOR: Tile? = null
    var SW_E_DOOR_OPEN: Boolean = false
    var S_N_DOOR: Tile? = null
    var S_N_DOOR_OPEN: Boolean = false
    var S_W_DOOR: Tile? = null
    var S_W_DOOR_OPEN: Boolean = false
    var S_E_DOOR: Tile? = null
    var S_E_DOOR_OPEN: Boolean = false
    var SE_N_DOOR: Tile? = null
    var SE_N_DOOR_OPEN: Boolean = false
    var SE_W_DOOR: Tile? = null
    var SE_W_DOOR_OPEN: Boolean = false
    var SE_S_DOOR: Tile? = null
    var SE_S_DOOR_OPEN: Boolean = false
    var SE_E_DOOR: Tile? = null
    var SE_E_DOOR_OPEN: Boolean = false
    fun resetDoorState() {
        NEROOM = Rectangular(NEbottomLeft, NEtopRight)
        NWbottomLeft = Tile.of(3540, 9717, 0)
        NWtopRight = Tile.of(3529, 9706, 0)
        NWROOM = Rectangular(NWbottomLeft!!, NWtopRight!!)
        SWbottomLeft = Tile.of(3529, 9683, 0)
        SWtopRight = Tile.of(3540, 9672, 0)
        SWROOM = Rectangular(SWbottomLeft!!, SWtopRight!!)
        SEbottomLeft = Tile.of(3563, 9672, 0)
        SEtopRight = Tile.of(3574, 9683, 0)
        SEROOM = Rectangular(SEbottomLeft!!, SEtopRight!!)
        CbottomLeft = Tile.of(3557, 9689, 0)
        CtopRight = Tile.of(3546, 9700, 0)
        CROOM = Rectangular(CbottomLeft!!, CtopRight!!)
        NW_N_DOOR = Tile.of(3535, 9718, 0)
        NW_N_DOOR_OPEN = false
        NW_W_DOOR = Tile.of(3528, 9712, 0)
        NW_W_DOOR_OPEN = false
        NW_S_DOOR = Tile.of(3534, 9705, 0)
        NW_S_DOOR_OPEN = false
        NW_E_DOOR = Tile.of(3541, 9711, 0)
        NW_E_DOOR_OPEN = false
        N_W_DOOR = Tile.of(3545, 9712, 0)
        N_W_DOOR_OPEN = false
        N_S_DOOR = Tile.of(3551, 9705, 0)
        N_S_DOOR_OPEN = false
        N_E_DOOR = Tile.of(3558, 9711, 0)
        N_E_DOOR_OPEN = false
        NE_N_DOOR = Tile.of(3569, 9718, 0)
        NE_N_DOOR_OPEN = false
        NE_W_DOOR = Tile.of(3562, 9712, 0)
        NE_W_DOOR_OPEN = false
        NE_S_DOOR = Tile.of(3568, 9705, 0)
        NE_S_DOOR_OPEN = false
        NE_E_DOOR = Tile.of(3575, 9711, 0)
        NE_E_DOOR_OPEN = false
        W_N_DOOR = Tile.of(3535, 9701, 0)
        W_N_DOOR_OPEN = false
        W_S_DOOR = Tile.of(3534, 9688, 0)
        W_S_DOOR_OPEN = false
        W_E_DOOR = Tile.of(3541, 9694, 0)
        W_E_DOOR_OPEN = false
        C_N_DOOR = Tile.of(3552, 9701, 0)
        C_N_DOOR_OPEN = false
        C_W_DOOR = Tile.of(3545, 9695, 0)
        C_W_DOOR_OPEN = false
        C_S_DOOR = Tile.of(3551, 9688, 0)
        C_S_DOOR_OPEN = false
        C_E_DOOR = Tile.of(3558, 9694, 0)
        C_E_DOOR_OPEN = false
        E_N_DOOR = Tile.of(3569, 9701, 0)
        E_N_DOOR_OPEN = false
        E_W_DOOR = Tile.of(3562, 9695, 0)
        E_W_DOOR_OPEN = false
        E_S_DOOR = Tile.of(3568, 9688, 0)
        E_S_DOOR_OPEN = false
        SW_N_DOOR = Tile.of(3535, 9684, 0)
        SW_N_DOOR_OPEN = false
        SW_W_DOOR = Tile.of(3528, 9678, 0)
        SW_W_DOOR_OPEN = false
        SW_S_DOOR = Tile.of(3534, 9671, 0)
        SW_S_DOOR_OPEN = false
        SW_E_DOOR = Tile.of(3541, 9677, 0)
        SW_E_DOOR_OPEN = false
        S_N_DOOR = Tile.of(3552, 9684, 0)
        S_N_DOOR_OPEN = false
        S_W_DOOR = Tile.of(3545, 9678, 0)
        S_W_DOOR_OPEN = false
        S_E_DOOR = Tile.of(3558, 9677, 0)
        S_E_DOOR_OPEN = false
        SE_N_DOOR = Tile.of(3569, 9684, 0)
        SE_N_DOOR_OPEN = false
        SE_W_DOOR = Tile.of(3562, 9678, 0)
        SE_W_DOOR_OPEN = false
        SE_S_DOOR = Tile.of(3568, 9671, 0)
        SE_S_DOOR_OPEN = false
        SE_E_DOOR = Tile.of(3575, 9677, 0)
        SE_E_DOOR_OPEN = false
    }
}

fun BarrowsBrothers.printStatus() {
    updateData()
    println("Ahrim killed: $ahrimKilled")
    println("Dharok killed: $dharockKilled")
    println("Guthan killed: $guthanKilled")
    println("Karil killed: $karilKilled")
    println("Torag killed: $toragKilled")
    println("Verac killed: $verakKilled")
    println("Akrisae killed: $akrisaeKilled")
    println("Linza killed: $linzaKilled")
    println("Hidden tunnel: $hiddenTunnel")
    println("Puzzle solved: $puzzleSolved")
    println("Chest opened: $chestOpened")
    println("Chest claimed: $chestClaimed")
}

class Banking : State<BarrowsBrothers>() {
    override suspend fun BarrowsBrothers.checkNext(): State<BarrowsBrothers>? {
        if (hasBanked) {
            hasBanked = false
            if (BarrowsArea.contains(localPlayer.tile))
                return Fight()

            println("Walking to Barrows area")
            return walkToBarrows
        }

        return null
    }

    val bankArea = Rectangular(Tile.of(3509, 3477, 0), Tile.of(3512, 3483, 0))
    var hasBanked = false

    override suspend fun BarrowsBrothers.stateLoop() {
        if (localPlayer.tile.getDistance(Tile.of(3511, 3480, 0)) > 10 &&
            localPlayer.tile.getDistance(Tile.of(3511, 3480, 0)) < 50
        ) {
            walkTo(bankArea.getRandomCoordinate(), true)
            hasBanked = false
            delayUntil { bankArea.contains(localPlayer.tile) }
            return
        } else {
            if (localPlayer.tile.getDistance(Tile.of(3511, 3480, 0)) > 50) {
                useLodestone(Lodestone.CANIFIS)
                delay(600)
                hasBanked = false
                return
            } else {
                hasBanked = loadLastPresetClosestBank()
                delay(5000)
            }
        }
    }
}

var BarrowsArea = Rectangular(Tile.of(3548, 3272, 0), Tile.of(3576, 3305, 0))

val listOfSteps =
    mutableListOf(
        Tile.of(3499, 3492, 0),
        Tile.of(3516, 3512, 0),
        Tile.of(3542, 3496, 0),
        Tile.of(3590, 3458, 0),
        Tile.of(3603, 3426, 0),
        Tile.of(3583, 3391, 0),
        Tile.of(3522, 3387, 0),
        Tile.of(3521, 3339, 0),
        Tile.of(3543, 3315, 0),
        Tile.of(3565, 3315, 0),
        Tile.of(3566, 3300, 0)
    )

private val walkToBarrows
    get() = traversal(Fight(), { BarrowsArea.contains(localPlayer.tile) }) {
        chebychevPath(localPlayer.tile, listOfSteps) { localPlayer.tile.getDistance(listOfSteps.last()) <= 5 }
    }

class Fight : State<BarrowsBrothers>() {
    override suspend fun BarrowsBrothers.checkNext(): State<BarrowsBrothers>? {
        if (localPlayer.tile.mapSquareId == 14231) {
            resetDoorState()
            return CryptWalk()
        }

        return null
    }

    fun BarrowsBrothers.getKillOrder(): String? {
        updateData()

        if (BarrowsArea.contains(localPlayer.tile)) {
            println("PLAYER IN BARROWS AREA")
            BarrowsReversePath.clear()
        }
        if (!ahrimKilled && hiddenTunnel != "Ahrim") return "Ahrim"
        if (!linzaKilled && hiddenTunnel != "Linza") return "Linza"
        if (!dharockKilled && hiddenTunnel != "Dharok") return "Dharok"
        if (!guthanKilled && hiddenTunnel != "Guthan") return "Guthan"
        if (!karilKilled && hiddenTunnel != "Karil") return "Karil"
        if (!toragKilled && hiddenTunnel != "Torag") return "Torag"
        if (!verakKilled && hiddenTunnel != "Verac") return "Verac"
        if (!akrisaeKilled && hiddenTunnel != "Akrisae") return "Akrisae"

        return null
    }

    var brotherToKill: String? = null
    val ahrimArea = Rectangular(Tile.of(3549, 9625, 0), Tile.of(3559, 9634, 0))
    val dharokArea = Rectangular(Tile.of(3550, 9647, 0), Tile.of(3559, 9655, 0))
    val guthanArea = Rectangular(Tile.of(3529, 9636, 0), Tile.of(3539, 9644, 0))
    val karilArea = Rectangular(Tile.of(3529, 9610, 0), Tile.of(3540, 9619, 0))
    val toragArea = Rectangular(Tile.of(3563, 9609, 0), Tile.of(3571, 9618, 0))
    val veracArea = Rectangular(Tile.of(3569, 9638, 0), Tile.of(3578, 9645, 0))

    override suspend fun BarrowsBrothers.stateLoop() {
        printStatus()
        BarrowsReversePath.clear()
        if (!inCombat ||
            (!ahrimArea.contains(localPlayer.tile) &&
                    !dharokArea.contains(localPlayer.tile) &&
                    !guthanArea.contains(localPlayer.tile) &&
                    !karilArea.contains(localPlayer.tile) &&
                    !toragArea.contains(localPlayer.tile) &&
                    !veracArea.contains(localPlayer.tile))
        ) {
            val btk = getKillOrder()
            brotherToKill = btk
        }

        if(!inCombat && checkWorldPop()) {
            return
        }

        println(brotherToKill)
        when (brotherToKill) {
            "Ahrim" -> {
                if (ahrimKilled) {
                    allObjects
                        .firstOrNull {
                            it.name() == "Staircase" && it.tile == Tile.of(3557, 9634, 0)
                        }
                        ?.interact("Climb-up")
                    delay(1200)
                    return
                }

                if (!ahrimArea.contains(localPlayer.tile)) {
                    val spade =
                        allObjects.firstOrNull {
                            it.name() == "Spades" && it.tile == Tile.of(3567, 3288, 0)
                        }
                    if (spade != null) {
                        spade.interact("Dig-with")
                        delayUntil(10000) { ahrimArea.contains(localPlayer.tile) }
                    }
                    return
                }

                killBrother()
            }

            "Dharok" -> {
                if (dharockKilled) {
                    allObjects
                        .firstOrNull {
                            it.name() == "Staircase" && it.tile == Tile.of(3557, 9655, 0)
                        }
                        ?.interact("Climb-up")
                    delay(1200)
                    return
                }

                if (!dharokArea.contains(localPlayer.tile)) {
                    val spade =
                        allObjects.firstOrNull {
                            it.name() == "Spades" && it.tile == Tile.of(3575, 3298, 0)
                        }
                    if (spade != null) {
                        spade.interact("Dig-with")
                        delayUntil(10000) { dharokArea.contains(localPlayer.tile) }
                    }
                    return
                }
                killBrother()
            }

            "Guthan" -> {
                if (guthanKilled) {
                    allObjects
                        .firstOrNull {
                            it.name() == "Staircase" && it.tile == Tile.of(3529, 9641, 0)
                        }
                        ?.interact("Climb-up")
                    delay(1200)
                    return
                }

                if (!guthanArea.contains(localPlayer.tile)) {
                    val spade =
                        allObjects.firstOrNull {
                            it.name() == "Spade" && it.tile == Tile.of(3576, 3281, 0)
                        }
                    if (spade != null) {
                        spade.interact("Dig-with")
                        delayUntil(10000) { guthanArea.contains(localPlayer.tile) }
                    }
                    return
                }
                killBrother()
            }

            "Karil" -> {
                if (karilKilled) {
                    allObjects
                        .firstOrNull {
                            it.name() == "Staircase" && it.tile == Tile.of(3530, 9617, 0)
                        }
                        ?.interact("Climb-up")

                    delay(1200)
                    return
                }

                if (!karilArea.contains(localPlayer.tile)) {
                    val spade =
                        allObjects.firstOrNull {
                            it.name() == "Spade" && it.tile == Tile.of(3564, 3277, 0)
                        }
                    if (spade != null) {
                        spade.interact("Dig-with")
                        delayUntil(10000) { karilArea.contains(localPlayer.tile) }
                    }
                    return
                }
                killBrother()
            }

            "Torag" -> {
                if (toragKilled) {
                    allObjects
                        .firstOrNull {
                            it.name() == "Staircase" && it.tile == Tile.of(3563, 9610, 0)
                        }
                        ?.interact("Climb-up")

                    delay(1200)
                    return
                }

                if (!toragArea.contains(localPlayer.tile)) {
                    val spade =
                        allObjects.firstOrNull {
                            it.name() == "Spades" && it.tile == Tile.of(3554, 3282, 0)
                        }
                    if (spade != null) {
                        spade.interact("Dig-with")
                        delayUntil(10000) { toragArea.contains(localPlayer.tile) }
                    }
                    return
                }
                killBrother()
            }

            "Verac" -> {
                if (verakKilled) {
                    allObjects
                        .firstOrNull {
                            it.name() == "Staircase" && it.tile == Tile.of(3578, 9639, 0)
                        }
                        ?.interact("Climb-up")

                    delay(1200)
                    return
                }

                if (!veracArea.contains(localPlayer.tile)) {
                    val spade =
                        allObjects.firstOrNull {
                            it.name() == "Spade" && it.tile == Tile.of(3557, 3298, 0)
                        }
                    if (spade != null) {
                        spade.interact("Dig-with")
                        delayUntil(10000) { veracArea.contains(localPlayer.tile) }
                    }
                    return
                }
                killBrother()
            }

            "Akrisae" -> {
                println("Fighting Akrisae")
            }

            "Linza" -> {
                println("Fighting Linza")
            }

            else -> {
                println("All brothers defeated")
                println("Hidden Tunnel: $hiddenTunnel")
                when (hiddenTunnel) {
                    "Ahrim" -> {
                        if (!ahrimArea.contains(localPlayer.tile)) {
                            val spade =
                                allObjects.firstOrNull {
                                    it.name() == "Spades" && it.tile == Tile.of(3567, 3288, 0)
                                }
                            if (spade != null) {
                                spade.interact("Dig-with")
                                delayUntil(10000) { ahrimArea.contains(localPlayer.tile) }
                            }
                            return
                        }
                    }

                    "Dharok" -> {
                        if (!dharokArea.contains(localPlayer.tile)) {
                            val spade =
                                allObjects.firstOrNull {
                                    it.name() == "Spades" && it.tile == Tile.of(3575, 3298, 0)
                                }
                            if (spade != null) {
                                spade.interact("Dig-with")
                                delayUntil(10000) { dharokArea.contains(localPlayer.tile) }
                            }
                            return
                        }
                    }

                    "Guthan" -> {
                        if (!guthanArea.contains(localPlayer.tile)) {
                            val spade =
                                allObjects.firstOrNull {
                                    it.name() == "Spade" && it.tile == Tile.of(3576, 3281, 0)
                                }
                            if (spade != null) {
                                spade.interact("Dig-with")
                                delayUntil(10000) { guthanArea.contains(localPlayer.tile) }
                            }
                            return
                        }
                    }

                    "Karil" -> {
                        if (!karilArea.contains(localPlayer.tile)) {
                            val spade =
                                allObjects.firstOrNull {
                                    it.name() == "Spade" && it.tile == Tile.of(3564, 3277, 0)
                                }
                            if (spade != null) {
                                spade.interact("Dig-with")
                                delayUntil(10000) { karilArea.contains(localPlayer.tile) }
                            }
                            return
                        }
                    }

                    "Torag" -> {
                        if (!toragArea.contains(localPlayer.tile)) {
                            val spade =
                                allObjects.firstOrNull {
                                    it.name() == "Spades" && it.tile == Tile.of(3554, 3282, 0)
                                }
                            if (spade != null) {
                                spade.interact("Dig-with")
                                delayUntil(10000) { toragArea.contains(localPlayer.tile) }
                            }
                            return
                        }
                    }

                    "Verac" -> {
                        if (!veracArea.contains(localPlayer.tile)) {
                            val spade =
                                allObjects.firstOrNull {
                                    it.name() == "Spade" && it.tile == Tile.of(3557, 3298, 0)
                                }
                            if (spade != null) {
                                spade.interact("Dig-with")
                                delayUntil(10000) { veracArea.contains(localPlayer.tile) }
                            }
                            return
                        }
                    }
                }

                if (!interfaces.isOpen(1186) && !interfaces.isOpen(1188)) {
                    findClosestObject("Sarcophagus", 10)?.let { sarcophagus ->
                        sarcophagus.interact("Search")
                        waitThenDelayUntil(1200, 10000) { interfaces.isOpen(1186) }
                    }
                }

                if (interfaces.isOpen(1186)) {
                    IFSlot(1186, 8, -1).dialogueContinue()
                    delayUntil(5000) { interfaces.isOpen(1188) }
                    return
                }
                if (interfaces.isOpen(1188)) {
                    IFSlot(1188, 8, -1).dialogueContinue()
                    delayUntil(5000) { localPlayer.tile.mapSquareId == 14231 }
                    return
                }
            }
        }
    }
}

class CryptWalk : State<BarrowsBrothers>() {
    override suspend fun BarrowsBrothers.checkNext(): State<BarrowsBrothers>? {
        if (chestClaimed)
            return ResetKill()
        if (BarrowsArea.contains(localPlayer.tile))
            return Fight()
        return null
    }

    override suspend fun BarrowsBrothers.stateLoop() {
        if (BarrowsArea.contains(localPlayer.tile)) {
            println("PLAYER IN BARROWS AREA")
            BarrowsReversePath.clear()
        }
        cryptWalkStarted = true

        printStatus()
        updateData()
        println("Crypt Walk")
        resetDoorState()
        val brotherNPC =
            npcs.values.firstOrNull {
                brotherNameArray.contains(it.name) &&
                        it.hasOption("Attack") &&
                        it.interactingWith(localPlayer)
            }
        if (brotherNPC != null) {
            if (localPlayer.interactionSid != brotherNPC.id) {
                brotherNPC.interact("Attack")
            }
            return
        }

        if (!CROOM!!.contains(localPlayer.tile)) {
            val portal =
                allObjects.firstOrNull {
                    it.name() == "Portal" && it.hasOption("Enter") && it.tile.withinDistance(localPlayer.tile, 5)
                }
            if (portal != null) {
                portal.interact("Enter")
                delayUntil(3000) { CROOM!!.contains(localPlayer.tile) }
                return
            } else {
                println("Portal not found, updating door status and navigating...")
                updateDoorsOpenStatus()
                try {
                    nodeupdates()
                } catch (e: Exception) {
                    println("Error calling nodeupdates: ${e.message}")
                    useLodestone(Lodestone.CANIFIS)
                }
            }
        } else {
            if (!chestOpened) {
                val chest =
                    allObjects.firstOrNull {
                        it.name() == "Chest" &&
                                it.hasOption("Open") &&
                                CROOM!!.contains(it.tile)
                    }
                if (chest != null) {
                    chest.interact("Open")
                    delayUntil(5000) { checkBitState(varps.getVar(1513), 16) }
                    return
                }
            } else {
                val chest =
                    allObjects.firstOrNull {
                        it.name() == "Chest" &&
                                it.hasOption("Quick loot") &&
                                CROOM!!.contains(it.tile)
                    }
                if (chest != null && allBrothersDead()) {
                    chest.interact("Quick loot")
                    delayUntil(600) { checkBitState(varps.getVar(1513), 26) }
                    return
                }
            }
        }
    }
}

class ResetKill : State<BarrowsBrothers>() {
    override suspend fun BarrowsBrothers.checkNext(): State<BarrowsBrothers>? {
        if (inventory.freeSlots < 3 && !chestClaimed) {
            BarrowsReversePath.clear()
            return Banking()
        }

        if (!chestClaimed && BarrowsArea.contains(localPlayer.tile)) {
            BarrowsReversePath.clear()
            return Fight()
        }
        return null
    }

    override suspend fun BarrowsBrothers.stateLoop() {
        updateData()
        resetDoorState()

        if (BarrowsArea.contains(localPlayer.tile)) {
            println("PLAYER IN BARROWS AREA")
            BarrowsReversePath.clear()
            return
        }

        val portal =
            allObjects.firstOrNull {
                it.name() == "Portal" && it.hasOption("Enter") && CROOM!!.contains(it.tile)
            }

        if (portal != null) {
            portal.interact("Enter")
            delayUntil(10000) { !CROOM!!.contains(localPlayer.tile) }

            val rope = allObjects.firstOrNull { it.name() == "Rope" && it.hasOption("Climb-up") }
            if (rope != null) {
                rope.interact("Climb-up")
                delayUntil(5000) {
                    allObjects.firstOrNull {
                        it.name() == "Staircase" && it.tile.getDistance(localPlayer.tile) < 5
                    } != null
                }
                allObjects
                    .filter { it.name() == "Staircase" }
                    .minByOrNull { it.tile.getDistance(localPlayer.tile) }
                    ?.interact("Climb-up")
                delayUntil(5000) { BarrowsArea.contains(localPlayer.tile) }
            }
            return
        } else {
            nodeupdates(true)
            val rope = allObjects.firstOrNull { it.name() == "Rope" && it.hasOption("Climb-up") }
            if (rope != null) {
                rope.interact("Climb-up")
                delayUntil(5000) {
                    allObjects.firstOrNull {
                        it.name() == "Staircase" && it.tile.getDistance(localPlayer.tile) < 5
                    } != null
                }
                allObjects
                    .filter { it.name() == "Staircase" }
                    .minByOrNull { it.tile.getDistance(localPlayer.tile) }
                    ?.interact("Climb-up")
                delayUntil(5000) { BarrowsArea.contains(localPlayer.tile) }
            }
        }
    }
}

var BarrowsReversePath = mutableListOf<String?>()

suspend fun BarrowsBrothers.nodeupdates(exit: Boolean = false) {
    try {
        println("Starting nodeupdates function")
        val barrowsGraph = Graph()
        barrowsGraph.resetGraph()
        val pathFinder = PathFinder(barrowsGraph)
        println("Created graph and pathfinder")
        barrowsGraph.addNode("NW")
        barrowsGraph.addNode("N")
        barrowsGraph.addNode("NE")
        barrowsGraph.addNode("W")
        barrowsGraph.addNode("C")
        barrowsGraph.addNode("E")
        barrowsGraph.addNode("SW")
        barrowsGraph.addNode("S")
        barrowsGraph.addNode("SE")
        println("Added all nodes to graph")
        if (NW_N_DOOR_OPEN) {
            barrowsGraph.connectNodes("NW", "NE", "NW_N_DOOR")
            println("Connected NW to NE via NW_N_DOOR")
        }

        if (NW_W_DOOR_OPEN) {
            barrowsGraph.connectNodes("NW", "SW", "NW_W_DOOR")
        }

        if (NW_S_DOOR_OPEN) {
            barrowsGraph.connectNodes("NW", "W", "NW_S_DOOR")
        }

        if (NW_E_DOOR_OPEN) {
            barrowsGraph.connectNodes("NW", "N", "NW_E_DOOR")
        }

        if (N_W_DOOR_OPEN) {
            barrowsGraph.connectNodes("N", "NW", "N_W_DOOR")
        }

        if (N_S_DOOR_OPEN) {
            barrowsGraph.connectNodes("N", "C", "N_S_DOOR")
        }

        if (N_E_DOOR_OPEN) {
            barrowsGraph.connectNodes("N", "NE", "N_E_DOOR")
        }

        if (NE_N_DOOR_OPEN) {
            barrowsGraph.connectNodes("NE", "NW", "NE_N_DOOR")
        }

        if (NE_W_DOOR_OPEN) {
            barrowsGraph.connectNodes("NE", "N", "NE_W_DOOR")
        }

        if (NE_S_DOOR_OPEN) {
            barrowsGraph.connectNodes("NE", "E", "NE_S_DOOR")
        }

        if (NE_E_DOOR_OPEN) {
            barrowsGraph.connectNodes("NE", "SE", "NE_E_DOOR")
        }

        if (W_N_DOOR_OPEN) {
            barrowsGraph.connectNodes("W", "NW", "W_N_DOOR_OPEN")
        }

        if (W_S_DOOR_OPEN) {
            barrowsGraph.connectNodes("W", "SW", "W_S_DOOR_OPEN")
        }

        if (W_E_DOOR_OPEN) {
            barrowsGraph.connectNodes("W", "C", "W_E_DOOR_OPEN")
        }

        if (C_N_DOOR_OPEN) {
            barrowsGraph.connectNodes("C", "N", "C_N_DOOR")
        }

        if (C_W_DOOR_OPEN) {
            barrowsGraph.connectNodes("C", "W", "C_W_DOOR")
        }

        if (C_S_DOOR_OPEN) {
            barrowsGraph.connectNodes("C", "S", "C_S_DOOR")
        }

        if (C_E_DOOR_OPEN) {
            barrowsGraph.connectNodes("C", "E", "C_E_DOOR")
        }

        if (E_N_DOOR_OPEN) {
            barrowsGraph.connectNodes("E", "NE", "E_N_DOOR")
        }

        if (E_W_DOOR_OPEN) {
            barrowsGraph.connectNodes("E", "C", "E_W_DOOR")
        }

        if (E_S_DOOR_OPEN) {
            barrowsGraph.connectNodes("E", "SE", "E_S_DOOR")
        }

        if (SW_N_DOOR_OPEN) {
            barrowsGraph.connectNodes("SW", "W", "SW_N_DOOR")
        }

        if (SW_W_DOOR_OPEN) {
            barrowsGraph.connectNodes("SW", "NW", "SW_W_DOOR")
        }

        if (SW_S_DOOR_OPEN) {
            barrowsGraph.connectNodes("SW", "SE", "SW_S_DOOR")
        }

        if (SW_E_DOOR_OPEN) {
            barrowsGraph.connectNodes("SW", "S", "SW_E_DOOR")
        }

        if (S_N_DOOR_OPEN) {
            barrowsGraph.connectNodes("S", "C", "S_N_DOOR")
        }

        if (S_W_DOOR_OPEN) {
            barrowsGraph.connectNodes("S", "SW", "S_W_DOOR")
        }

        if (S_E_DOOR_OPEN) {
            barrowsGraph.connectNodes("S", "SE", "S_E_DOOR")
        }

        if (SE_N_DOOR_OPEN) {
            barrowsGraph.connectNodes("SE", "E", "SE_N_DOOR")
        }

        if (SE_W_DOOR_OPEN) {
            barrowsGraph.connectNodes("SE", "S", "SE_W_DOOR")
        }

        if (SE_S_DOOR_OPEN) {
            barrowsGraph.connectNodes("SE", "SW", "SE_S_DOOR")
        }

        if (SE_E_DOOR_OPEN) {
            barrowsGraph.connectNodes("SE", "NE", "SE_E_DOOR")
        }

        println("Finding starting room...")
        findStartingRoom()
        println("Starting room found: $StartingRoom")
        val shortestPathNodes: MutableList<String?> = barrowsGraph.findShortestPath(StartingRoom, "C")
        var var10001: String? = StartingRoom
        println(
            "Shortest node path from " + var10001 + " to C (nodes): " + shortestPathNodes.toString()
        )
        val doorsPath = pathFinder.findDoorsForPath(StartingRoom, "C")
        if (BarrowsReversePath.isEmpty()) {
            BarrowsReversePath = doorsPath.asReversed()
        }
        var10001 = StartingRoom
        println("Doors to go through from " + var10001 + " to C: " + doorsPath.toString())
        for (door in doorsPath) {
            println("Door: $door")
        }

        println("Starting navigation...")
        if (!exit)
            navigatePath(doorsPath)
        else
            navigatePath(BarrowsReversePath)
        println("Finished nodeupdates function")

    } catch (e: Exception) {
        println("Error in nodeupdates: ${e.message}")
        e.printStackTrace()
    }
}

suspend fun BarrowsBrothers.navigatePath(doorsPath: MutableList<String?>) {
    println("Starting navigatePath with doors: $doorsPath")
    val doorCoordinates: MutableMap<String?, Tile?> = HashMap()
    doorCoordinates["NW_N_DOOR"] = NW_N_DOOR
    doorCoordinates["NW_W_DOOR"] = NW_W_DOOR
    doorCoordinates["NW_S_DOOR"] = NW_S_DOOR
    doorCoordinates["NW_E_DOOR"] = NW_E_DOOR
    doorCoordinates["N_W_DOOR"] = N_W_DOOR
    doorCoordinates["N_S_DOOR"] = N_S_DOOR
    doorCoordinates["N_E_DOOR"] = N_E_DOOR
    doorCoordinates["NE_N_DOOR"] = NE_N_DOOR
    doorCoordinates["NE_W_DOOR"] = NE_W_DOOR
    doorCoordinates["NE_S_DOOR"] = NE_S_DOOR
    doorCoordinates["NE_E_DOOR"] = NE_E_DOOR
    doorCoordinates["W_N_DOOR"] = W_N_DOOR
    doorCoordinates["W_S_DOOR"] = W_S_DOOR
    doorCoordinates["W_E_DOOR"] = W_E_DOOR
    doorCoordinates["C_N_DOOR"] = C_N_DOOR
    doorCoordinates["C_W_DOOR"] = C_W_DOOR
    doorCoordinates["C_S_DOOR"] = C_S_DOOR
    doorCoordinates["C_E_DOOR"] = C_E_DOOR
    doorCoordinates["E_N_DOOR"] = E_N_DOOR
    doorCoordinates["E_W_DOOR"] = E_W_DOOR
    doorCoordinates["E_S_DOOR"] = E_S_DOOR
    doorCoordinates["SW_N_DOOR"] = SW_N_DOOR
    doorCoordinates["SW_W_DOOR"] = SW_W_DOOR
    doorCoordinates["SW_S_DOOR"] = SW_S_DOOR
    doorCoordinates["SW_E_DOOR"] = SW_E_DOOR
    doorCoordinates["S_N_DOOR"] = S_N_DOOR
    doorCoordinates["S_W_DOOR"] = S_W_DOOR
    doorCoordinates["S_E_DOOR"] = S_E_DOOR
    doorCoordinates["SE_N_DOOR"] = SE_N_DOOR
    doorCoordinates["SE_W_DOOR"] = SE_W_DOOR
    doorCoordinates["SE_S_DOOR"] = SE_S_DOOR
    doorCoordinates["SE_E_DOOR"] = SE_E_DOOR
    if (!doorsPath.isEmpty() && doorsPath.size >= 2) {
        println("Door path has ${doorsPath.size} doors to navigate")

        cryptWalkStarted = true

        for (doorName in doorsPath) {
            println("Processing door: $doorName")
            val specificDoorCoordinate: Tile? = doorCoordinates[doorName]
            if (specificDoorCoordinate != null) {
                println("Found coordinate for door $doorName: $specificDoorCoordinate")
                val success = interactWithSpecificDoor(specificDoorCoordinate)
                if (!success) {
                    println("Exiting navigation due to failure.")

                    val item = Equipment.Slot.getItem(Equipment.Slot.RING)
                    if (item?.name == "Ring of fortune") {
                        item.click("Grand Exchange")
                    }
                    delay(random(3800, 4500))

                    cryptWalkStarted = false

                    break
                } else {
                    println("Successfully passed through door: $doorName")
                }
            } else {
                println("No coordinate found for door: " + doorName)
            }
        }
        println("Finished navigating all doors")
    } else {
        println("Door path empty or too short (size: ${doorsPath.size})")
        cryptWalkStarted = false
    }
}

fun BarrowsBrothers.findStartingRoom() {
    println("Finding starting room...")
    val rope = allObjects.firstOrNull { it.name() == "Rope" }
    if (rope != null) {
        val ropeLocation: Tile = rope.tile
        println("Found rope at location: $ropeLocation")

        if (NEROOM!!.contains(ropeLocation)) {
            StartingRoom = "NE"
            println("Starting room determined: NE")
        } else if (NWROOM!!.contains(ropeLocation)) {
            StartingRoom = "NW"
            println("Starting room determined: NW")
        } else if (SWROOM!!.contains(ropeLocation)) {
            StartingRoom = "SW"
            println("Starting room determined: SW")
        } else if (SEROOM!!.contains(ropeLocation)) {
            StartingRoom = "SE"
            println("Starting room determined: SE")
        } else {
            StartingRoom = "Null"
            println("Starting room could not be determined, set to Null")
        }
    } else {
        println("Rope not found")
    }
}

fun BarrowsBrothers.updateDoorsOpenStatus() {
    println("Updating doors open status...")
    val doors = allObjects.filter { it.name() == "Door" }
    println("Found ${doors.size} doors")

    for (door in doors) {
        println("Checking door at ${door.tile}")
        if (door.hasOption("Open")) {
            println("Door at ${door.tile} has Open option - marking as closed")
            updateDoorStatusBasedOnLocation(door.tile, true)
        }

        if (!door.hasOption("Open")) {
            println("Door at ${door.tile} does not have Open option - marking as open")
            updateDoorStatusBasedOnLocation(door.tile, false)
        }
    }
    println("Finished updating door statuses")
}

private fun BarrowsBrothers.updateDoorStatusBasedOnLocation(location: Tile, isOpen: Boolean) {
    println("Updating door status for location $location to isOpen: $isOpen")
    if (location == this.NW_N_DOOR) {
        this.NW_N_DOOR_OPEN = isOpen
        println("Updated NW_N_DOOR_OPEN to $isOpen")
    } else if (location == this.NW_W_DOOR) {
        this.NW_W_DOOR_OPEN = isOpen
    } else if (location == this.NW_S_DOOR) {
        this.NW_S_DOOR_OPEN = isOpen
    } else if (location == this.NW_E_DOOR) {
        this.NW_E_DOOR_OPEN = isOpen
    } else if (location == this.N_W_DOOR) {
        this.N_W_DOOR_OPEN = isOpen
    } else if (location == this.N_S_DOOR) {
        this.N_S_DOOR_OPEN = isOpen
    } else if (location == this.N_E_DOOR) {
        this.N_E_DOOR_OPEN = isOpen
    } else if (location == this.NE_N_DOOR) {
        this.NE_N_DOOR_OPEN = isOpen
    } else if (location == this.NE_W_DOOR) {
        this.NE_W_DOOR_OPEN = isOpen
    } else if (location == this.NE_S_DOOR) {
        this.NE_S_DOOR_OPEN = isOpen
    } else if (location == this.NE_E_DOOR) {
        this.NE_E_DOOR_OPEN = isOpen
    } else if (location == this.W_N_DOOR) {
        this.W_N_DOOR_OPEN = isOpen
    } else if (location == this.W_S_DOOR) {
        this.W_S_DOOR_OPEN = isOpen
    } else if (location == this.W_E_DOOR) {
        this.W_E_DOOR_OPEN = isOpen
    } else if (location == this.C_N_DOOR) {
        this.C_N_DOOR_OPEN = isOpen
    } else if (location == this.C_W_DOOR) {
        this.C_W_DOOR_OPEN = isOpen
    } else if (location == this.C_S_DOOR) {
        this.C_S_DOOR_OPEN = isOpen
    } else if (location == this.C_E_DOOR) {
        this.C_E_DOOR_OPEN = isOpen
    } else if (location == this.E_N_DOOR) {
        this.E_N_DOOR_OPEN = isOpen
    } else if (location == this.E_W_DOOR) {
        this.E_W_DOOR_OPEN = isOpen
    } else if (location == this.E_S_DOOR) {
        this.E_S_DOOR_OPEN = isOpen
    } else if (location == this.SW_N_DOOR) {
        this.SW_N_DOOR_OPEN = isOpen
    } else if (location == this.SW_W_DOOR) {
        this.SW_W_DOOR_OPEN = isOpen
    } else if (location == this.SW_S_DOOR) {
        this.SW_S_DOOR_OPEN = isOpen
    } else if (location == this.SW_E_DOOR) {
        this.SW_E_DOOR_OPEN = isOpen
    } else if (location == this.S_N_DOOR) {
        this.S_N_DOOR_OPEN = isOpen
    } else if (location == this.S_W_DOOR) {
        this.S_W_DOOR_OPEN = isOpen
    } else if (location == this.S_E_DOOR) {
        this.S_E_DOOR_OPEN = isOpen
    } else if (location == this.SE_N_DOOR) {
        this.SE_N_DOOR_OPEN = isOpen
    } else if (location == this.SE_W_DOOR) {
        this.SE_W_DOOR_OPEN = isOpen
    } else if (location == this.SE_S_DOOR) {
        this.SE_S_DOOR_OPEN = isOpen
    } else if (location == this.SE_E_DOOR) {
        this.SE_E_DOOR_OPEN = isOpen
        println("Updated SE_E_DOOR_OPEN to $isOpen")
    } else {
        println("No matching door found for location: $location")
    }
}

suspend fun BarrowsBrothers.interactWithSpecificDoor(specificDoor: Tile?): Boolean {
    println("Attempting to interact with door at: $specificDoor")
    var success = false
    var retries = 0
    val startTime = System.currentTimeMillis()
    val maxWaitTime = 30000L
    val initialValue: Int = varps.getVar(3141)
    println("Initial var 3141 value: $initialValue")

    while (System.currentTimeMillis() - startTime < maxWaitTime) {
        delay(random(100, 200))
        val currentValue = varps.getVar(3141)
        val valueChanged = currentValue != initialValue

        if (valueChanged) {
            println("Made it past door - var changed from $initialValue to $currentValue")
            success = true
            break
        }

        val door =
            allObjects.filter { it.name() == "Door" }.minByOrNull {
                it.tile.getDistance(specificDoor!!)
            }
        if (door != null) {
            println(
                "Found door at ${door.tile}, distance: ${door.tile.getDistance(specificDoor!!)}"
            )
            if (!localPlayer.isMoving && door.tile == specificDoor && !interfaces.isOpen(25)) {
                println("Attempting to interact with door...")
                if (!door.interact("Open")) {
                    println("Interacting with door: false")
                    ++retries
                    println("Attempt " + retries + " failed, retrying...")
                    delay(random(1100, 1700))
                    break
                }

                println("Interacting with door: true")
                delay(random(700, 1400))
            } else {
                println(
                    "Cannot interact - isMoving: ${localPlayer.isMoving}, doorMatch: ${door.tile == specificDoor}, puzzleOpen: ${
                        interfaces.isOpen(
                            25
                        )
                    }"
                )
            }

            if (interfaces.isOpen(25)) {
                println("Puzzle interface is open, solving...")
                delay(random(10, 40))
                val TunnelData: Int = varps.getVar(1512)
                getPuzzleSolution(TunnelData)
                delay(random(700, 1200))
                println("Puzzle complete interacting with door: " + door.interact("Open"))
            }

            if (localPlayer.isMoving &&
                door.tile.getDistance(localPlayer.tile) >= 7.0 &&
                Ability.SURGE.offCd
            ) {
                println("More then 7 Tiles away from door attempting to Surge: " + surge())
                delay(random(300, 660))
                println("Attemping to reinteract with door: " + door.interact("Open"))
                delay(random(700, 2000))
            }
        } else {
            println("No door found near $specificDoor")
        }
    }

    println("Door interaction finished with success: $success")
    return success
}

suspend fun BarrowsBrothers.killBrother() {
    val brotherNPC =
        npcs.values.firstOrNull {
            brotherNameArray.contains(it.name) &&
                    it.hasOption("Attack") &&
                    it.interactingWith(localPlayer)
        }
    if (brotherNPC == null) {
        findClosestObject("Sarcophagus", 10)?.let { sarcophagus ->
            sarcophagus.interact("Search")
            waitThenDelayUntil(1200, 10000) {
                npcs.values.firstOrNull {
                    brotherNameArray.contains(it.name) &&
                            it.hasOption("Attack") &&
                            it.interactingWith(localPlayer)
                } != null
            }
        }
    } else {
        if (localPlayer.interactionSid != brotherNPC.id) {
            brotherNPC.interact("Attack")
        }
        return
    }
}
