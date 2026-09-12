package com.projectx.script.impl.gibson.farming

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.random

@ScriptDescription(
    name = "Samaden Seed Farming",
    version = "1.0.0",
    author = "Billy",
    description = "Automated Samadem seed farming using state machine pattern.",
)
class FarmingLeagues : StateMachineScript<FarmingLeagues>(), ConfigurableScript {
    val selectedPatch = EnumConfigItem(
        name = "Samadem Patch",
        description = "Select which Samadem patch to farm",
        enumValues = SamdemSeedPatch.entries.toTypedArray(),
        initialValue = SamdemSeedPatch.SamademArea
    )

    val enableDebug = BooleanConfigItem(
        name = "Debug Mode",
        description = "Enable debug output",
        initialValue = true
    )

    val autoBank = BooleanConfigItem(
        name = "Auto Banking",
        description = "Automatically bank when inventory is full or needs supplies",
        initialValue = true
    )

    val currentPatch: SamdemSeedPatch
        get() = selectedPatch.value

    val debug: Boolean
        get() = enableDebug.value

    val bankingEnabled: Boolean
        get() = autoBank.value

    var seedsPlanted = 0
    var harvests = 0

    val bankingCoord: Tile = Tile(3269, 3167, 0)
    val bankingArea: Area = Area.Circular(bankingCoord, 10.0)

    override fun getStartState() = CheckingSamdemPatch()

    override fun onEvent(event: Event) {
        super.onEvent(event)
        if (event is Chat && debug) {
            println("[CHAT] ${event.message}")
        }
    }

    private fun findPatchObject(action: String): SceneObject? {
        val obj = when (action) {
            "Inspect" -> findClosestObjectWithOption("Inspect")
            "Interact" -> findClosestObject { it.name().contains("Vine herb patch", ignoreCase = true) }
                ?: findClosestObject { it.name().contains("vine", ignoreCase = true) }
            else -> findClosestObjectWithOption(action)
        }
        if (obj == null) {
            if (debug) println("[ERROR] No object found for action: $action")
            return null
        }
        if (debug) println("[FOUND] Object for \"$action\": ${obj.name()} (ID: ${obj.id})")
        return obj
    }

    suspend fun rakePatch() {
        if (debug) println("[ACTION] Raking the patch...")
        val success = interactClosestObject("Samdem patch", "Rake")
        if (!success && debug) println("[ERROR] Could not find or interact with Samdem patch for raking")
        delay(1200)
    }

    suspend fun plantSeed() {
        if (debug) println("[PLANTING] Planting Samdem seed...")

        val patchObj = findPatchObject("Inspect") ?: run {
            if (debug) println("[ERROR] No Vine herb patch found to plant seed")
            return
        }
        val seed = inventory.getItem("Samaden seed")
        if (seed == null) {
            if (debug) println("[ERROR] No Samaden seed found in inventory")
            return
        }
        if (seed.useOn(patchObj)) {
            if (debug) println("[SUCCESS] Successfully used Samadem seed on patch")
            seedsPlanted++
            delay(random(700, 1000))
            try {
                delayUntil(30000) {
                    currentPatch.detectPatchState().contains("Growing")
                }
                if (debug) println("[SUCCESS] Samadem seed planted successfully")
            } catch (e: Exception) {
                if (debug) println("[WARNING] Samadem seed planting timeout reached")
            }
        } else {
            if (debug) println("[ERROR] Failed to use Samadem seed on patch")
        }
    }

    suspend fun waitForGrowth() {
        if (debug) println("[ACTION] Waiting for plant to grow...")
        delay(5000)
    }

    suspend fun harvestPlant() {
        if (debug) println("[ACTION] Harvesting Samdem plant...")
        val success = interactClosestObject("Samdem patch", "Harvest")
        if (success) harvests++
        else if (debug) println("[ERROR] Could not find or interact with Samdem patch for harvesting")
        delay(1200)
    }

    suspend fun pickFruit() {
        if (debug) println("[ACTION] Picking fruit from Samdem plant...")
        val success = interactClosestObject("Vine herbs", "Pick")
        if (success) harvests++
        else if (debug) println("[ERROR] Could not find or interact with Samdem patch for picking")
        delay(1200)
    }
}

class CheckingSamdemPatch : State<FarmingLeagues>() {
    override suspend fun FarmingLeagues.checkNext(): State<FarmingLeagues>? {
        if (debug) println("[TRANSITION CHECK] CheckingSamdemPatch - checking next state...")
        if (inventory.isFull) {
            if (debug) println("[TRANSITION] Inventory is full, switching to Banking")
            return if (bankingEnabled) BankingSamdem() else null
        }
        val currentDistance = currentPatch.location.getDistance(localPlayer.tile)
        if (debug) println("[LOCATION CHECK] Distance to ${currentPatch.locationName}: $currentDistance tiles")
        return null
    }
    override suspend fun FarmingLeagues.stateLoop() {
        if (debug) {
            println("=== SAMDEM SEED STATE: CheckingSamdemPatch ===")
            println("[STATUS] Current patch: ${currentPatch.locationName}")
            println("[STATUS] Player location: ${localPlayer.tile}")
            println("[STATUS] Patch location: ${currentPatch.location}")
            println("[STATUS] Seeds planted: $seedsPlanted")
            println("[STATUS] Harvests: $harvests")
        }
        val state = currentPatch.detectPatchState()
        if (debug) println("[PATCH STATE] Current state: $state")
        when {
            state.contains("Needs to be raked") -> {
                if (debug) println("[ACTION] Patch needs raking")
                rakePatch()
            }
            state.contains("Already raked") -> {
                if (debug) println("[ACTION] Patch is ready for planting")
                plantSeed()
            }
            state.contains("Growing") -> {
                if (debug) println("[ACTION] Plant is growing")
                waitForGrowth()
            }
            state.contains("Ready to be picked") -> {
                if (debug) println("[ACTION] Plant ready for harvest")
                harvestPlant()
            }
            state.contains("Pick") -> {
                if (debug) println("[ACTION] Fruit ready for picking")
                pickFruit()
            }
            else -> {
                if (debug) println("[ACTION] Unknown or unhandled state")
            }
        }
    }
}

class BankingSamdem : State<FarmingLeagues>() {
    override suspend fun FarmingLeagues.checkNext(): State<FarmingLeagues>? = null
    override suspend fun FarmingLeagues.stateLoop() {
        if (debug) println("[BANKING] Banking for Samdem seed farming...")
    }
}

class WalkingToSamdemPatch : State<FarmingLeagues>() {
    override suspend fun FarmingLeagues.checkNext(): State<FarmingLeagues>? = null
    override suspend fun FarmingLeagues.stateLoop() {
        if (debug) println("[WALKING] Walking to Samdem patch...")
    }
}

enum class SamdemSeedPatch(
    val patchName: String,
    val locationName: String,
    val location: Tile,
    val varbitId: Int,
    val questId: Int
) {
    SamademArea("Samdem Patch", "Karamaja", Tile(2956, 2606, 0), 16079, 0);

    fun detectPatchState(): String {
        val varValue = varps.getVarBit(this.varbitId)
        return when {
            varValue in 0..2 -> "Needs to be raked"
            varValue == 3 -> "Already raked"
            varValue in 47..51 -> "Growing"
            varValue == 52 -> "Ready to be picked"
            varValue == 53-> "Pick"
            else -> "Unknown state: $varValue"
        }
    }
}
