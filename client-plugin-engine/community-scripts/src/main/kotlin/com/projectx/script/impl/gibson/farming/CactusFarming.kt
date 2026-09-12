package com.projectx.script.impl.gibson.farming

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.random

@ScriptDescription(
    name = "Cactus Farming",
    version = "1.0.0",
    author = "Gibson",
    description = "Fully automated cactus farming using state machine pattern for Golden dragonfruit cultivation.",
)
class CactusFarming : StateMachineScript<CactusFarming>(), ConfigurableScript {
    val selectedPatch = EnumConfigItem(
        name = "Cactus Patch",
        description = "Select which cactus patch to farm",
        enumValues = CactusPatch.entries.toTypedArray(),
        initialValue = CactusPatch.AL_KHARID
    )

    val enableDebug = BooleanConfigItem(
        name = "Debug Mode",
        description = "Enable debug output",
        initialValue = true
    )

    val useGrowthPotion = BooleanConfigItem(
        name = "Use Growth Potion",
        description = "Automatically use Supreme growth potion to speed up growth",
        initialValue = true
    )

    val autoBank = BooleanConfigItem(
        name = "Auto Banking",
        description = "Automatically bank when inventory is full or needs supplies",
        initialValue = true
    )

    val currentPatch: CactusPatch
        get() = selectedPatch.value

    val debug: Boolean
        get() = enableDebug.value

    val usePotion: Boolean
        get() = useGrowthPotion.value

    val bankingEnabled: Boolean
        get() = autoBank.value

    var fruitsHarvested = 0
    var seedsPlanted = 0

    val bankingCoord: Tile = Tile(3269, 3167, 0)
    val bankingArea: Area = Area.Circular(bankingCoord, 10.0)

    override fun getStartState() = CheckingPatch()

    override fun onEvent(event: Event) {
        super.onEvent(event)
        if (event is Chat && debug) {
            println("[CHAT] ${event.message}")
        }
    }

    }

class CheckingPatch : State<CactusFarming>() {
    override suspend fun CactusFarming.checkNext(): State<CactusFarming>? {
        if (debug) println("[TRANSITION CHECK] CheckingPatch - checking next state...")

        if (inventory.isFull) {
            if (debug) println("[TRANSITION] Inventory is full, switching to Banking")
            return if (bankingEnabled) Banking() else null
        }

        val currentDistance = currentPatch.location.getDistance(localPlayer.tile)
        if (debug) println("[LOCATION CHECK] Distance to ${currentPatch.locationName}: $currentDistance tiles")

        if (currentDistance >= 10) {
            if (debug) println("[TRANSITION] Too far from cactus patch, switching to WalkingToPatch")
            return WalkingToPatch()
        }

        if (!hasRequiredItems()) {
            if (debug) println("[TRANSITION] Missing required items, switching to Banking")
            return if (bankingEnabled) Banking() else null
        }

        return null
    }

    override suspend fun CactusFarming.stateLoop() {
        if (debug) {
            println("=== CACTUS FARMING STATE: CheckingPatch ===")
            println("[STATUS] Current patch: ${currentPatch.locationName}")
            println("[STATUS] Player location: ${localPlayer.tile}")
            println("[STATUS] Patch location: ${currentPatch.location}")
            println("[STATUS] Seeds planted: $seedsPlanted")
            println("[STATUS] Fruits harvested: $fruitsHarvested")
        }

        if (currentPatch.questId != 0 && !isQuestComplete(currentPatch.questId)) {
            if (debug) println("[ERROR] Quest requirement not met for ${currentPatch.locationName}")
            delay(5000)
            return
        }

        moveToCurrentPatch()

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
                if (usePotion) useGrowthPotion() else waitForGrowth()
            }
            state.contains("Ready to be picked") -> {
                if (debug) println("[ACTION] Plant ready for health check")
                checkHealth()
            }
            state.contains("Pick") -> {
                if (debug) println("[ACTION] Fruit ready for harvesting")
                pickFruit()
            }
            state.contains("Clear") -> {
                if (debug) println("[ACTION] Patch needs clearing")
                clearPatch()
            }
            state.contains("Diseased") -> {
                if (debug) println("[ACTION] Plant is diseased, curing")
                curePatch()
            }
            state.contains("Dead") -> {
                if (debug) println("[ACTION] Plant is dead, clearing")
                clearPatch()
            }
            else -> {
                if (debug) println("[ERROR] Unknown patch state: $state")
                delay(1000)
            }
        }
    }

    private suspend fun CactusFarming.rakePatch() {
        if (debug) println("[RAKING] Starting to rake patch...")

        val patchObj = findPatchObject("Rake")
        if (patchObj == null) {
            if (debug) println("[ERROR] No rake-able patch found")
            return
        }

        if (!patchObj.interact("Rake")) {
            if (debug) println("[ERROR] Failed to interact with patch for raking")
            return
        }

        if (debug) println("[RAKING] Waiting for patch to be raked...")
        try {
            delayUntil(30000) {
                currentPatch.detectPatchState().contains("Already raked")
            }
            if (debug) println("[SUCCESS] Patch raked successfully")
        } catch (e: Exception) {
            if (debug) println("[WARNING] Raking timeout reached")
        }
    }

    private suspend fun CactusFarming.plantSeed() {
        if (debug) println("[PLANTING] Planting Golden dragonfruit seed...")

        val patchObj = findPatchObject("Inspect") ?: return
        val seed = inventory.getItem("Golden dragonfruit seed")

        if (seed == null) {
            if (debug) println("[ERROR] No Golden dragonfruit seed found in inventory")
            return
        }

        if (seed.useOn(patchObj)) {
            if (debug) println("[SUCCESS] Successfully used seed on patch")
            seedsPlanted++

            delay(random(700, 1000))

            try {
                delayUntil(30000) {
                    currentPatch.detectPatchState().contains("Growing")
                }
                if (debug) println("[SUCCESS] Seed planted successfully")
            } catch (e: Exception) {
                if (debug) println("[WARNING] Seed planting timeout reached")
            }
        } else {
            if (debug) println("[ERROR] Failed to use seed on patch")
        }
    }

    private suspend fun CactusFarming.useGrowthPotion() {
        if (debug) println("[POTION] Using Supreme growth potion...")

        val potion = inventory.getItem("Supreme growth potion") ?:
                    inventory.getItem("Supreme growth potion (leafy)")

        if (potion == null) {
            if (debug) println("[ERROR] No Supreme growth potion found")
            return
        }

        val patchObj = findPatchObject("Interact") ?:
                      findClosestObject { it.name().contains("cactus", ignoreCase = true) }

        if (patchObj == null) {
            if (debug) println("[ERROR] No cactus patch found for potion use")
            return
        }

        if (potion.useOn(patchObj)) {
            if (debug) println("[SUCCESS] Successfully used growth potion")

            delay(random(700, 1000))

            try {
                delayUntil(30000) {
                    currentPatch.detectPatchState().contains("Ready to be picked")
                }
                if (debug) println("[SUCCESS] Plant grew successfully with potion")
            } catch (e: Exception) {
                if (debug) println("[WARNING] Growth potion timeout reached")
            }
        } else {
            if (debug) println("[ERROR] Failed to use growth potion")
        }
    }

    private suspend fun CactusFarming.waitForGrowth() {
        if (debug) println("[WAITING] Waiting for natural growth...")
        delay(5000)
    }

    private suspend fun CactusFarming.checkHealth() {
        if (debug) println("[HEALTH] Checking plant health...")

        val healthObj = findPatchObject("Check-health")
        if (healthObj == null) {
            if (debug) println("[ERROR] No health check object found")
            return
        }

        if (!healthObj.interact("Check-health")) {
            if (debug) println("[ERROR] Failed to check health")
            return
        }

        if (debug) println("[HEALTH] Health check initiated...")
        try {
            delayUntil(30000) {
                currentPatch.detectPatchState().contains("Pick")
            }
            if (debug) println("[SUCCESS] Health check completed")
        } catch (e: Exception) {
            if (debug) println("[WARNING] Health check timeout reached")
        }
    }

    private suspend fun CactusFarming.pickFruit() {
        if (debug) println("[PICKING] Picking Golden dragonfruit...")

        val fruitObj = findPatchObject("Pick") ?:
                      findClosestObject { it.name().contains("cactus", ignoreCase = true) }

        if (fruitObj == null) {
            if (debug) println("[ERROR] No fruit object found for picking")
            return
        }

        delay(random(400, 600))

        if (!fruitObj.interact("Pick")) {
            if (debug) println("[ERROR] Failed to pick fruit")
            return
        }

        if (debug) println("[PICKING] Picking initiated...")
        try {
            delayUntil(60000) {
                currentPatch.detectPatchState().contains("Clear")
            }
            if (debug) println("[SUCCESS] All fruit picked successfully")
            fruitsHarvested++
        } catch (e: Exception) {
            if (debug) println("[WARNING] Fruit picking timeout reached")
        }
    }

    private suspend fun CactusFarming.clearPatch() {
        if (debug) println("[CLEARING] Clearing the patch...")

        val clearObj = findPatchObject("Clear")
        if (clearObj == null) {
            if (debug) println("[ERROR] No clear object found")
            return
        }

        if (!clearObj.interact("Clear")) {
            if (debug) println("[ERROR] Failed to clear patch")
            return
        }

        delayUntil(6000) { interfaces.isOpen(1188) }
        if (interfaces.isOpen(1188)) {
            delay(random(500, 1000))
            if (debug) println("[DIALOG] Handling clear dialog")
        }

        try {
            delayUntil(30000) {
                val state = currentPatch.detectPatchState()
                state.contains("Already raked") || state.contains("Needs to be raked")
            }
            if (debug) println("[SUCCESS] Patch cleared successfully")
        } catch (e: Exception) {
            if (debug) println("[WARNING] Patch clearing timeout reached")
        }
    }

    private suspend fun CactusFarming.curePatch() {
        if (debug) println("[CURING] Curing diseased patch...")

        val diseasedObj = findPatchObject("Cure") ?: findPatchObject("Prune")
        if (diseasedObj == null) {
            if (debug) println("[ERROR] No cure/prune object found")
            return
        }

        val action = if (diseasedObj.hasOption("Cure")) "Cure" else "Prune"
        if (!diseasedObj.interact(action)) {
            if (debug) println("[ERROR] Failed to $action diseased patch")
            return
        }

        if (debug) println("[CURING] Curing initiated...")
        try {
            delayUntil(30000) {
                !currentPatch.detectPatchState().contains("Diseased")
            }
            if (debug) println("[SUCCESS] Patch cured successfully")
        } catch (e: Exception) {
            if (debug) println("[WARNING] Patch curing timeout reached")
        }
    }

    private suspend fun CactusFarming.moveToCurrentPatch() {
        if (localPlayer.tile.getDistance(currentPatch.location) <= 3) {
            if (debug) println("[LOCATION] Already at patch location")
            return
        }

        if (debug) println("[WALKING] Moving to ${currentPatch.locationName}")
        walkTo(currentPatch.location, false)
        delay(random(1000, 1500))
    }

    private fun CactusFarming.findPatchObject(action: String): SceneObject? {
        val obj = when (action) {
            "Inspect" -> findClosestObjectWithOption("Inspect")
            "Interact" -> findClosestObject { it.name().contains("Cactus patch", ignoreCase = true) } ?:
                         findClosestObject { it.name().contains("cactus", ignoreCase = true) }
            else -> findClosestObjectWithOption(action)
        }

        if (obj == null) {
            if (debug) println("[ERROR] No object found for action: $action")
            return null
        }

        if (debug) {
            println("[FOUND] Object for \"$action\": ${obj.name()} (ID: ${obj.id})")
        }
        return obj
    }

    private fun CactusFarming.hasRequiredItems(): Boolean {
        val hasSeed = inventory.hasItem("Golden dragonfruit seed")
        val hasPotion = if (usePotion) {
            inventory.hasItem("Supreme growth potion") ||
            inventory.hasItem("Supreme growth potion (leafy)")
        } else true

        if (debug) {
            println("[INVENTORY] Has seed: $hasSeed, Has potion: $hasPotion")
        }

        return hasSeed && hasPotion
    }

    private fun isQuestComplete(questId: Int): Boolean {
        return true
    }
}

class WalkingToPatch : State<CactusFarming>() {
    override suspend fun CactusFarming.checkNext(): State<CactusFarming>? {
        if (debug) println("[TRANSITION CHECK] WalkingToPatch - checking if arrived...")

        val distance = currentPatch.location.getDistance(localPlayer.tile)
        if (debug) println("[LOCATION] Distance to patch: $distance")

        if (distance <= 10) {
            if (debug) println("[TRANSITION] Arrived at patch, switching to CheckingPatch")
            return CheckingPatch()
        }

        return null
    }

    override suspend fun CactusFarming.stateLoop() {
        if (debug) {
            println("=== CACTUS FARMING STATE: WalkingToPatch ===")
            println("[WALKING] Target: ${currentPatch.locationName} at ${currentPatch.location}")
            println("[WALKING] Current location: ${localPlayer.tile}")
            println("[WALKING] Distance: ${currentPatch.location.getDistance(localPlayer.tile)}")
        }

        if (debug) println("[WALKING] Walking to cactus patch...")
        walkTo(currentPatch.location, false)
        delay(random(1000, 2000))
    }
}

class Banking : State<CactusFarming>() {
    override suspend fun CactusFarming.checkNext(): State<CactusFarming>? {
        if (debug) println("[TRANSITION CHECK] Banking - checking next state...")

        val inBankArea = bankingArea.contains(localPlayer.tile)
        val needsSupplies = !hasRequiredItems()
        val inventoryFull = inventory.isFull

        if (debug) {
            println("[BANKING CHECK] In bank area: $inBankArea")
            println("[BANKING CHECK] Needs supplies: $needsSupplies")
            println("[BANKING CHECK] Inventory full: $inventoryFull")
        }

        if (!inBankArea) {
            if (debug) println("[TRANSITION] Not in bank area, switching to WalkingToBank")
            return WalkingToBank()
        }

        if (!needsSupplies && !inventoryFull) {
            if (debug) println("[TRANSITION] Banking complete, returning to CheckingPatch")
            return CheckingPatch()
        }

        return null
    }

    override suspend fun CactusFarming.stateLoop() {
        if (debug) {
            println("=== CACTUS FARMING STATE: Banking ===")
            println("[BANKING] Current location: ${localPlayer.tile}")
            println("[BANKING] Inventory full: ${inventory.isFull}")
        }

        if (debug) println("[BANKING] Banking functionality - load preset or get supplies")

        delay(2000)
    }

    private fun CactusFarming.hasRequiredItems(): Boolean {
        val hasSeed = inventory.hasItem("Golden dragonfruit seed")
        val hasPotion = if (usePotion) {
            inventory.hasItem("Supreme growth potion") ||
            inventory.hasItem("Supreme growth potion (leafy)")
        } else true

        return hasSeed && hasPotion
    }
}

class WalkingToBank : State<CactusFarming>() {
    override suspend fun CactusFarming.checkNext(): State<CactusFarming>? {
        if (debug) println("[TRANSITION CHECK] WalkingToBank - checking if arrived...")

        val inBankArea = bankingArea.contains(localPlayer.tile)
        val distance = bankingCoord.getDistance(localPlayer.tile)

        if (debug) {
            println("[BANK LOCATION] Distance to bank: $distance")
            println("[BANK LOCATION] In banking area: $inBankArea")
        }

        if (inBankArea) {
            if (debug) println("[TRANSITION] Arrived at bank, switching to Banking")
            return Banking()
        }

        return null
    }

    override suspend fun CactusFarming.stateLoop() {
        if (debug) {
            println("=== CACTUS FARMING STATE: WalkingToBank ===")
            println("[BANK WALKING] Target: Banking area at $bankingCoord")
            println("[BANK WALKING] Current location: ${localPlayer.tile}")
            println("[BANK WALKING] Distance: ${bankingCoord.getDistance(localPlayer.tile)}")
        }

        if (debug) println("[BANK WALKING] Walking to bank...")
        walkTo(bankingCoord, false)
        delay(random(1000, 2000))
    }
}
