package com.projectx.script.impl.gibson

import world.gregs.voidps.type.Tile
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.random
import kotlin.math.abs
import kotlin.math.max

@ScriptDescription(
    name = "Divination",
    version = "1.0.0",
    author = "Gibson",
    description = "Automated Divination script for harvesting Incandescent wisps and capturing chronicles with state machine pattern."
)
class Divination : StateMachineScript<Divination>(), ConfigurableScript {
    val harvestChronicles = BooleanConfigItem(
        name = "Harvest Chronicles",
        description = "Capture chronicle fragments when available",
        initialValue = true
    )

    val onlyFarmChronicles = BooleanConfigItem(
        name = "Only Farm Chronicles",
        description = "Only capture chronicles, don't harvest wisps",
        initialValue = false
    )

    val depositChronicles = BooleanConfigItem(
        name = "Deposit Chronicles",
        description = "Empower chronicle fragments at rift when inventory threshold reached",
        initialValue = true
    )

    val useDivineOMatic = BooleanConfigItem(
        name = "Use Divine-o-Matic",
        description = "Enable Divine-o-Matic vacuum functionality",
        initialValue = false
    )

    val enableDebug = BooleanConfigItem(
        name = "Debug Mode",
        description = "Enable debug output",
        initialValue = false
    )

    val shouldHarvestChronicles: Boolean get() = harvestChronicles.value
    val shouldOnlyFarmChronicles: Boolean get() = onlyFarmChronicles.value
    val shouldDepositChronicles: Boolean get() = depositChronicles.value
    val shouldUseDivineOMatic: Boolean get() = useDivineOMatic.value
    val debug: Boolean get() = enableDebug.value

    var memoriesConverted = 0
    var chroniclesCaptured = 0
    var chroniclesEmpowered = 0

    val incandescentWispArea = Area.Rectangular(
        Tile(2269, 3039, 0),
        Tile(2291, 3065, 0)
    )

    val allowedCoordinates = listOf(
        Tile(2279, 3045, 0), Tile(2279, 3046, 0), Tile(2279, 3047, 0),
        Tile(2279, 3048, 0), Tile(2279, 3049, 0), Tile(2279, 3050, 0),
        Tile(2280, 3045, 0), Tile(2280, 3046, 0), Tile(2280, 3047, 0),
        Tile(2280, 3048, 0), Tile(2280, 3049, 0), Tile(2280, 3050, 0),
        Tile(2281, 3046, 0), Tile(2281, 3047, 0), Tile(2281, 3048, 0),
        Tile(2281, 3049, 0), Tile(2281, 3050, 0), Tile(2282, 3046, 0),
        Tile(2282, 3047, 0), Tile(2282, 3048, 0), Tile(2282, 3049, 0),
        Tile(2282, 3050, 0), Tile(2283, 3045, 0), Tile(2283, 3046, 0),
        Tile(2283, 3047, 0), Tile(2283, 3048, 0), Tile(2283, 3049, 0),
        Tile(2283, 3050, 0), Tile(2284, 3045, 0), Tile(2284, 3046, 0),
        Tile(2284, 3047, 0), Tile(2284, 3048, 0), Tile(2284, 3049, 0)
    )

    enum class AccountType { IRONMAN, HARDCORE, REGULAR, HARDIRON }

    var hasDowser = false
    var chronicleCaptureSuccess = false
    var chronicleAlreadyCaught = false

    override fun getStartState() = CheckingArea()

    override fun onStart() {
        hasDowser = inventory.hasItem("Dowser") || equipment.hasItem("Dowser")

        if (debug) {
            println("=== DIVINATION SCRIPT STARTED ===")
            println("Has Dowser: $hasDowser")
            println("Harvest Chronicles: $shouldHarvestChronicles")
            println("Only Farm Chronicles: $shouldOnlyFarmChronicles")
        }
    }

    override fun onEvent(event: Event) {
        if (event is Chat) {
            handleChatMessage(event.message)
            if (debug) println("[CHAT] ${event.message}")
        }
    }

    private fun handleChatMessage(message: String) {
        when {
            message.contains("You capture a chronicle fragment") -> {
                chronicleCaptureSuccess = true
                chroniclesCaptured++
                if (debug) println("[SUCCESS] Chronicle captured! Total: $chroniclesCaptured")
            }
            message.contains("This chronicle has already been caught") -> {
                chronicleAlreadyCaught = true
                if (debug) println("[WARNING] Chronicle already caught")
            }
            message.contains("You successfully convert") -> {
                memoriesConverted++
                if (debug) println("[SUCCESS] Memories converted! Total: $memoriesConverted")
            }
            message.contains("You empower") -> {
                chroniclesEmpowered++
                if (debug) println("[SUCCESS] Chronicles empowered! Total: $chroniclesEmpowered")
            }
        }
    }

    fun getAccountType(): AccountType {
        val ironman = varps.getVarBit(20806)
        val hardcore = varps.getVarBit(20807)

        return when {
            ironman == 1 && hardcore == 1 -> AccountType.HARDIRON
            hardcore == 1 -> AccountType.HARDCORE
            ironman == 1 && hardcore == 0 -> AccountType.IRONMAN
            else -> AccountType.REGULAR
        }
    }

    private fun isWispReachable(wispCoordinate: Tile): Boolean {
        return allowedCoordinates.any { coord ->
            max(abs(coord.x - wispCoordinate.x), abs(coord.y - wispCoordinate.y)) <= 7
        }
    }

    fun getNearestAllowedCoordinate(from: Tile): Tile {
        return allowedCoordinates.minByOrNull { from.getDistance(it) } ?: from
    }
}

class CheckingArea : State<Divination>() {
    override suspend fun Divination.checkNext(): State<Divination>? {
        if (debug) println("[TRANSITION CHECK] CheckingArea")

        if (!incandescentWispArea.contains(localPlayer.tile)) {
            println("=== SCRIPT STOPPED ===")
            println("You are not in the Incandescent Wisp area!")
            println("Please start the script while standing in the Incandescent Wisp area (Elder Halls)")
            println("Area coordinates: $incandescentWispArea")
            stop()
            return null
        }

        if (shouldUseDivineOMatic && inventory.hasItem("Divine-o-matic vacuum")) {
            if (debug) println("[TRANSITION] Using Divine-o-Matic")
            return UsingDivineOMatic()
        }

        if (shouldHarvestChronicles) {
            val accountType = getAccountType()

            val foundChronicle = if (accountType == Divination.AccountType.REGULAR) {
                interactClosestNPC(18205, "Capture") || interactClosestNPC(18204, "Capture")
            } else {
                interactClosestNPC(18204, "Capture")
            }

            if (foundChronicle) {
                if (debug) println("[TRANSITION] Chronicle found, capturing")
                return CapturingChronicle()
            }

            if (shouldDepositChronicles && inventory.count("Chronicle fragment") > 25) {
                if (debug) println("[TRANSITION] Too many chronicle fragments, empowering")
                return EmpoweringChronicles()
            }

            if (shouldOnlyFarmChronicles) {
                if (debug) println("[TRANSITION] Only farming chronicles, no chronicles found - continuing to check")
                return null
            }
        }

        if (inventory.isFull) {
            if (debug) println("[TRANSITION] Inventory full, converting memories")
            return ConvertingMemories()
        }

        if (debug) println("[TRANSITION] Ready to harvest")
        return HarvestingWisps()
    }

    override suspend fun Divination.stateLoop() {
        if (debug) {
            println("=== DIVINATION STATE: CheckingArea ===")
            println("[STATUS] Player location: ${localPlayer.tile}")
            println("[STATUS] In wisp area: ${incandescentWispArea.contains(localPlayer.tile)}")
        }
        delay(500, 1000)
    }
}

class UsingDivineOMatic : State<Divination>() {
    override suspend fun Divination.checkNext(): State<Divination>? {
        if (!inventory.hasItem("Divine-o-matic vacuum")) {
            if (debug) println("[TRANSITION] No Divine-o-Matic found")
            return CheckingArea()
        }
        return null
    }

    override suspend fun Divination.stateLoop() {
        if (debug) println("=== DIVINATION STATE: UsingDivineOMatic ===")

        if (inventory.hasItem("Divine-o-matic vacuum")) {
            if (debug) println("[ACTION] Activating Divine-o-Matic")
            inventory.clickItem("Divine-o-matic vacuum", "Activate")
            delay(2000, 3000)
        }
    }
}

class CapturingChronicle : State<Divination>() {
    override suspend fun Divination.checkNext(): State<Divination>? {
        val accountType = getAccountType()

        val foundChronicle = if (accountType == Divination.AccountType.REGULAR) {
            interactClosestNPC(18205, "Capture") || interactClosestNPC(18204, "Capture")
        } else {
            interactClosestNPC(18204, "Capture")
        }

        if (!foundChronicle) {
            if (debug) println("[TRANSITION] No chronicles found")
            return CheckingArea()
        }
        return null
    }

    override suspend fun Divination.stateLoop() {
        if (debug) println("=== DIVINATION STATE: CapturingChronicle ===")

        val accountType = getAccountType()

        chronicleCaptureSuccess = false
        chronicleAlreadyCaught = false

        if (debug) println("[ACTION] Attempting to capture chronicle")

        val interacted = if (accountType == Divination.AccountType.REGULAR) {
            interactClosestNPC(18205, "Capture") || interactClosestNPC(18204, "Capture")
        } else {
            interactClosestNPC(18204, "Capture")
        }

        if (interacted) {
            delayUntil(3000) {
                chronicleCaptureSuccess || chronicleAlreadyCaught
            }

            when {
                chronicleCaptureSuccess -> {
                    if (debug) println("[SUCCESS] Chronicle captured successfully")
                }
                chronicleAlreadyCaught -> {
                    if (debug) println("[WARNING] Chronicle already caught")
                }
                else -> {
                    if (debug) println("[WARNING] Chronicle capture timed out or failed")
                }
            }
        }

        delay(500, 800)
    }
}

class EmpoweringChronicles : State<Divination>() {
    override suspend fun Divination.checkNext(): State<Divination>? {
        if (!inventory.hasItem("Chronicle fragment")) {
            if (debug) println("[TRANSITION] No chronicle fragments to empower")
            return CheckingArea()
        }
        return null
    }

    override suspend fun Divination.stateLoop() {
        if (debug) println("=== DIVINATION STATE: EmpoweringChronicles ===")

        val chronicleCount = inventory.count("Chronicle fragment")
        if (debug) println("[ACTION] Empowering $chronicleCount chronicle fragments at rift")

        if (interactClosestReachableObject("Empower")) {
            delayUntil(10000L) { !inventory.hasItem("Chronicle fragment") }
            if (debug) println("[SUCCESS] Chronicle fragments empowered")
        } else {
            if (debug) println("[ERROR] No rift found for empowering chronicles")
        }
    }
}

class ConvertingMemories : State<Divination>() {
    override suspend fun Divination.checkNext(): State<Divination>? {
        if (!inventory.isFull) {
            val hasMemories = inventory.hasItem("Incandescent memory") || inventory.hasItem("Enriched incandescent memory")
            if (!hasMemories) {
                if (debug) println("[TRANSITION] No memories to convert")
                return CheckingArea()
            }
        }
        return null
    }

    override suspend fun Divination.stateLoop() {
        if (debug) println("=== DIVINATION STATE: ConvertingMemories ===")

        if (debug) println("[ACTION] Converting memories at rift")

        if (interactClosestReachableObject("Convert memories")) {
            delayUntil(30000L) {
                !inventory.hasItem("Incandescent memory") && !inventory.hasItem("Enriched incandescent memory")
            }
            if (debug) println("[SUCCESS] Memories converted")
        } else {
            if (debug) println("[ERROR] No rift found for converting memories")
        }
    }
}

class HarvestingWisps : State<Divination>() {
    override suspend fun Divination.checkNext(): State<Divination>? {
        if (inventory.isFull) {
            if (debug) println("[TRANSITION] Inventory full")
            return ConvertingMemories()
        }

        if (shouldHarvestChronicles) {
            val accountType = getAccountType()

            val foundChronicle = if (accountType == Divination.AccountType.REGULAR) {
                interactClosestNPC(18205, "Capture") || interactClosestNPC(18204, "Capture")
            } else {
                interactClosestNPC(18204, "Capture")
            }

            if (foundChronicle) {
                if (debug) println("[TRANSITION] Chronicle found while harvesting")
                return CapturingChronicle()
            }
        }

        return null
    }

    override suspend fun Divination.stateLoop() {
        if (debug) println("=== DIVINATION STATE: HarvestingWisps ===")

        if (localPlayer.isAnimating) {
            if (debug) println("[STATUS] Player is animating, waiting...")
            return
        }

        if (localPlayer.isMoving) {
            if (debug) println("[STATUS] Player is moving, waiting...")
            return
        }

        if (interactClosestNPC(18195, "Harvest")) {
            handleEnrichedSpringHarvesting()
            return
        }

        if (interactClosestNPC(18198, "Harvest")) {
            if (debug) println("[ACTION] Harvesting Incandescent wisp")
            delayUntil(random(5000L, 8000L)) { localPlayer.isAnimating }
            delayUntil(random(60000L, 120000L)) { !localPlayer.isAnimating || inventory.isFull }
        } else {
            if (debug) println("[WARNING] No wisps found, waiting...")
            delay(2000, 5000)
        }
    }

    private suspend fun Divination.handleEnrichedSpringHarvesting() {
        if (hasDowser) {
            if (localPlayer.isAnimating) {
                if (debug) println("[STATUS] Player already animating with Dowser")
                return
            }

            if (localPlayer.isMoving) {
                if (debug) println("[STATUS] Player moving with Dowser")
                return
            }

            if (!allowedCoordinates.contains(localPlayer.tile)) {
                val nearestAllowed = getNearestAllowedCoordinate(localPlayer.tile)
                if (localPlayer.tile.getDistance(nearestAllowed) <= 7) {
                    if (debug) println("[MOVEMENT] Moving to safe coordinate: $nearestAllowed")
                    walkTo(nearestAllowed, false)
                    delayUntil(15000L) { localPlayer.tile == nearestAllowed }
                } else {
                    if (debug) println("[WARNING] Nearest safe coordinate too far away")
                    return
                }
            }

            if (debug) println("[ACTION] Harvesting Enriched Spring with Dowser")
            delay(5000, 7000)

        } else {
            if (debug) println("[ACTION] Harvesting Enriched Spring")
        }
    }
}
