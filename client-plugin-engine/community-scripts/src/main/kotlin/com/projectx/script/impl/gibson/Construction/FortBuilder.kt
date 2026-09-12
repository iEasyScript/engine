package com.projectx.script.impl.gibson

import world.gregs.voidps.type.Tile
import com.projectx.script.*
import com.projectx.script.api.*

@ScriptDescription(
    name = "Fort Builder",
    version = "1.0.0",
    author = "Gibson",
    description =
        "Automatically builds all Fort Forinthry buildings in sequence with material requirements Note - Untick the add selected build and retick it. If it is not added when you start then tick and untick the Clear Queue and then add the building via the add selected building tick."
)
class FortBuilder : StateMachineScript<FortBuilder>(), ConfigurableScript {
    val selectedBuilding =
        EnumConfigItem(
            name = "Select Building",
            description = "Select a building to add to the queue",
            enumValues = BuildingType.entries.toTypedArray(),
            initialValue = BuildingType.WORKSHOP_T1
        )

    val addSelectedBuilding =
        BooleanConfigItem(
            name = "Add Selected Building",
            description = "Check to add the selected building to the queue",
            initialValue = false
        )

    val clearQueue =
        BooleanConfigItem(
            name = "Clear Queue",
            description = "Check to clear the current queue",
            initialValue = false
        )

    val debugQueueFlag =
        BooleanConfigItem(
            name = "Debug Queue",
            description = "Check to print the current queue contents",
            initialValue = true
        )

    val activePlayMode =
        BooleanConfigItem(
            name = "Active Play Mode",
            description = "Enable faster delays for active play vs AFK mode",
            initialValue = true
        )

    val repeatQueueEnabled =
        BooleanConfigItem(
            name = "Repeat Queue",
            description = "Restart the building queue when all buildings are completed",
            initialValue = false
        )

    val enableDebugOutput =
        BooleanConfigItem(
            name = "Debug Output",
            description = "Enable detailed debug messages in console",
            initialValue = true
        )

    val enableAdvancedPathfinding =
        BooleanConfigItem(
            name = "Advanced Pathfinding",
            description = "Use advanced pathfinding with route calculation instead of simple walking",
            initialValue = true
        )

    val useCustomDelays =
        BooleanConfigItem(
            name = "Use Custom Delays",
            description = "Enable custom delay settings instead of automatic timing",
            initialValue = false
        )

    val minDelayMs =
        IntConfigItem(
            name = "Min Delay (ms)",
            description = "Minimum delay between actions in milliseconds",
            initialValue = 800
        )

    val maxDelayMs =
        IntConfigItem(
            name = "Max Delay (ms)",
            description = "Maximum delay between actions in milliseconds",
            initialValue = 1200
        )

    val activePlay: Boolean
        get() = activePlayMode.value
    val repeatQueue: Boolean
        get() = repeatQueueEnabled.value
    val debugMode: Boolean
        get() = enableDebugOutput.value

    var hasClickedBuild = false
    var isCurrentlyBuilding = false
    var hasSubmittedPlan = false

    val buildingList =
        arrayOf(
            "Workshop (Tier 1)",
            "Workshop (Tier 2)",
            "Workshop (Tier 3)",
            "Town hall (Tier 1)",
            "Town hall (Tier 2)",
            "Town hall (Tier 3)",
            "Chapel (Tier 1)",
            "Chapel (Tier 2)",
            "Chapel (Tier 3)",
            "Command centre (Tier 1)",
            "Command centre (Tier 2)",
            "Command centre (Tier 3)",
            "Kitchen (Tier 1)",
            "Kitchen (Tier 2)",
            "Kitchen (Tier 3)",
            "Guardhouse (Tier 1)",
            "Guardhouse (Tier 2)",
            "Guardhouse (Tier 3)",
            "Grove cabin (Tier 1)",
            "Grove cabin (Tier 2)",
            "Grove cabin (Tier 3)",
            "Rangers workroom (Tier 1)",
            "Rangers workroom (Tier 2)",
            "Rangers workroom (Tier 3)",
            "Botanist's Workbench (Tier 1)",
            "Botanist's Workbench (Tier 2)",
            "Botanist's Workbench (Tier 3)"
        )

    var taskQueue: MutableList<String> = mutableListOf()
    var currentTaskIndex = 0
    private var lastCompletedTask: String? = null

    override fun getStartState(): State<FortBuilder> {
        println("Starting FortBuilder script")
        try {
            initializeQueueBasedOnMode()
            println("Queue initialization completed with ${taskQueue.size} items")
            println("Queue after initialization: ${taskQueue.joinToString(", ")}")
        } catch (e: Exception) {
            println("Error initializing queue: ${e.message}")
            e.printStackTrace()
            taskQueue = mutableListOf("Workshop (Tier 1)")
            currentTaskIndex = 0
            println("Created fallback queue with 1 item: Workshop (Tier 1)")
        }
        return CheckState()
    }

    enum class BuildingType(val displayName: String, val buildingName: String) {
        WORKSHOP_T1("Workshop T1", "Workshop (Tier 1)"),
        WORKSHOP_T2("Workshop T2", "Workshop (Tier 2)"),
        WORKSHOP_T3("Workshop T3", "Workshop (Tier 3)"),
        TOWN_HALL_T1("Town Hall T1", "Town hall (Tier 1)"),
        TOWN_HALL_T2("Town Hall T2", "Town hall (Tier 2)"),
        TOWN_HALL_T3("Town Hall T3", "Town hall (Tier 3)"),
        CHAPEL_T1("Chapel T1", "Chapel (Tier 1)"),
        CHAPEL_T2("Chapel T2", "Chapel (Tier 2)"),
        CHAPEL_T3("Chapel T3", "Chapel (Tier 3)"),
        COMMAND_CENTRE_T1("Command Centre T1", "Command centre (Tier 1)"),
        COMMAND_CENTRE_T2("Command Centre T2", "Command centre (Tier 2)"),
        COMMAND_CENTRE_T3("Command Centre T3", "Command centre (Tier 3)"),
        KITCHEN_T1("Kitchen T1", "Kitchen (Tier 1)"),
        KITCHEN_T2("Kitchen T2", "Kitchen (Tier 2)"),
        KITCHEN_T3("Kitchen T3", "Kitchen (Tier 3)"),
        GUARDHOUSE_T1("Guardhouse T1", "Guardhouse (Tier 1)"),
        GUARDHOUSE_T2("Guardhouse T2", "Guardhouse (Tier 2)"),
        GUARDHOUSE_T3("Guardhouse T3", "Guardhouse (Tier 3)"),
        GROVE_CABIN_T1("Grove Cabin T1", "Grove cabin (Tier 1)"),
        GROVE_CABIN_T2("Grove Cabin T2", "Grove cabin (Tier 2)"),
        GROVE_CABIN_T3("Grove Cabin T3", "Grove cabin (Tier 3)"),
        RANGERS_WORKROOM_T1("Rangers Workroom T1", "Rangers workroom (Tier 1)"),
        RANGERS_WORKROOM_T2("Rangers Workroom T2", "Rangers workroom (Tier 2)"),
        RANGERS_WORKROOM_T3("Rangers Workroom T3", "Rangers workroom (Tier 3)"),
        BOTANIST_WORKBENCH_T1("Botanist's Workbench T1", "Botanist's Workbench (Tier 1)"),
        BOTANIST_WORKBENCH_T2("Botanist's Workbench T2", "Botanist's Workbench (Tier 2)"),
        BOTANIST_WORKBENCH_T3("Botanist's Workbench T3", "Botanist's Workbench (Tier 3)");

        override fun toString(): String = displayName
    }

    private fun debugPrint(message: String) {
        if (enableDebugOutput.value == true) {
            println("[FortBuilder Debug] $message")
        }
    }

    private suspend fun smartDelay(minMs: Int = 800, maxMs: Int = 1200) {
        if (useCustomDelays.value == true) {
            val min = minDelayMs.value
            val max = maxDelayMs.value
            delay(min, max)
        } else {
            val isActive = activePlayMode.value
            delay(if (isActive) minMs else (minMs * 3), if (isActive) maxMs else (maxMs * 8))
        }
    }

    fun initializeQueueBasedOnMode() {
        taskQueue = mutableListOf()
        currentTaskIndex = 0
        println("Queue initialized as empty. Use the 'Add Selected Building' button to add buildings to the queue.")
    }

    val buildingRequirements =
        mapOf(
            "Workshop (Tier 1)" to mapOf("Wooden frame" to 8, "Stone wall segment" to 6),
            "Workshop (Tier 2)" to mapOf("Teak frame" to 20, "Stone wall segment" to 6),
            "Workshop (Tier 3)" to mapOf("Yew frame" to 48, "Stone wall segment" to 6),
            "Town hall (Tier 1)" to mapOf("Oak frame" to 10, "Stone wall segment" to 6),
            "Town hall (Tier 2)" to mapOf("Maple frame" to 22, "Stone wall segment" to 6),
            "Town hall (Tier 3)" to mapOf("Magic frame" to 60, "Stone wall segment" to 6),
            "Chapel (Tier 1)" to mapOf("Oak frame" to 10, "Stone wall segment" to 6),
            "Chapel (Tier 2)" to mapOf("Acadia frame" to 24, "Stone wall segment" to 6),
            "Chapel (Tier 3)" to mapOf("Elder frame" to 50, "Stone wall segment" to 6),
            "Command centre (Tier 1)" to
                    mapOf("Willow frame" to 12, "Stone wall segment" to 6),
            "Command centre (Tier 2)" to
                    mapOf("Yew frame" to 26, "Stone wall segment" to 6),
            "Command centre (Tier 3)" to
                    mapOf("Elder frame" to 80, "Stone wall segment" to 6),
            "Kitchen (Tier 1)" to mapOf("Willow frame" to 12, "Stone wall segment" to 6),
            "Kitchen (Tier 2)" to mapOf("Acadia frame" to 22, "Stone wall segment" to 6),
            "Kitchen (Tier 3)" to mapOf("Magic frame" to 50, "Stone wall segment" to 6),
            "Guardhouse (Tier 1)" to mapOf("Maple frame" to 14, "Stone wall segment" to 6),
            "Guardhouse (Tier 2)" to
                    mapOf("Mahogany frame" to 26, "Stone wall segment" to 6),
            "Guardhouse (Tier 3)" to mapOf("Elder frame" to 70, "Stone wall segment" to 6),
            "Grove cabin (Tier 1)" to mapOf("Wooden frame" to 8, "Stone wall segment" to 6),
            "Grove cabin (Tier 2)" to mapOf("Teak frame" to 20, "Stone wall segment" to 6),
            "Grove cabin (Tier 3)" to
                    mapOf("Mahogany frame" to 48, "Stone wall segment" to 6),
            "Rangers workroom (Tier 1)" to
                    mapOf("Acadia frame" to 14, "Stone wall segment" to 6),
            "Rangers workroom (Tier 2)" to
                    mapOf("Mahogany frame" to 24, "Stone wall segment" to 6),
            "Rangers workroom (Tier 3)" to
                    mapOf("Magic frame" to 42, "Stone wall segment" to 6),
            "Botanist's Workbench (Tier 1)" to
                    mapOf("Acadia frame" to 4, "Stone wall segment" to 6),
            "Botanist's Workbench (Tier 2)" to
                    mapOf("Yew frame" to 8, "Stone wall segment" to 6),
            "Botanist's Workbench (Tier 3)" to
                    mapOf("Elder frame" to 12, "Stone wall segment" to 6)
        )

    fun hasRequiredMaterials(building: String): Boolean {
        val requirements = buildingRequirements[building] ?: return false

        for ((materialName, requiredAmount) in requirements) {
            val inventoryCount = inventory
                .filter { it.name.contains(materialName, ignoreCase = true) }
                .sumOf { it.amount }
            if (inventoryCount < requiredAmount) {
                debugPrint(
                    "Missing materials for $building: need $requiredAmount $materialName, have $inventoryCount"
                )
                return false
            }
        }
        return true
    }

    fun getCurrentBuilding(): String? {
        return if (taskQueue.isNotEmpty() && currentTaskIndex < taskQueue.size) {
            taskQueue[currentTaskIndex]
        } else null
    }

    fun moveToNextTask() {
        if (taskQueue.isEmpty()) {
            println("Task queue is empty, cannot move to next task")
            return
        }

        if (currentTaskIndex < taskQueue.size - 1) {
            currentTaskIndex++
            debugPrint("Moving to next task: ${getCurrentBuilding()}")
        } else {
            val shouldRepeat = repeatQueueEnabled.value

            if (shouldRepeat && taskQueue.isNotEmpty()) {
                currentTaskIndex = 0
                println("Restarting queue from beginning")
            } else {
                println("All tasks completed!")
            }
        }
    }

    fun addBuildingToQueue(buildingName: String) {
        println("Manually adding $buildingName to queue")
        taskQueue.add(buildingName)
        println("Current queue: ${taskQueue.joinToString(", ")}")
    }

    fun onConfigUpdated() {
        println("Config updated event triggered")
        if (addSelectedBuilding.value) {
            val buildingName = selectedBuilding.value.buildingName
            println("Config update: Adding $buildingName to queue")
            taskQueue.add(buildingName)
            println("Current queue: ${taskQueue.joinToString(", ")}")
            addSelectedBuilding.value = false
        }

        if (clearQueue.value) {
            println("Config update: Clearing queue")
            taskQueue.clear()
            currentTaskIndex = 0
            println("Queue cleared")
            clearQueue.value = false
        }

        if (debugQueueFlag.value) {
            println("DEBUG: Current queue size: ${taskQueue.size}")
            println("DEBUG: Current queue contents: ${taskQueue.joinToString(", ")}")
            println("DEBUG: Current task index: $currentTaskIndex")
            println("DEBUG: Current building: ${getCurrentBuilding()}")
            debugQueueFlag.value = false
        }
    }
}

class CheckState : State<FortBuilder>() {
    private var firstCheck = true

    override suspend fun FortBuilder.checkNext(): State<FortBuilder>? {
        val constructionHotspot = allObjects.firstOrNull { it.name() == "Optimal Construction hotspot" }
        val regularHotspot = allObjects.firstOrNull { it.name() == "Construction hotspot" }

        if (firstCheck) {
            println("First check in CheckState")
            println("Queue size: ${taskQueue.size}")
            println("Queue contents: ${taskQueue.joinToString(", ")}")
            firstCheck = false
        }

        if (constructionHotspot != null && isCurrentlyBuilding) {
            return ContinueBuilding()
        }

        if (taskQueue.isEmpty()) {
            println("No tasks in queue")
            return null
        }

        val currentBuilding = getCurrentBuilding()
        if (currentBuilding == null) {
            println("No current building task")
            return null
        }

        if (!hasRequiredMaterials(currentBuilding)) {
            println("Missing materials for $currentBuilding")
            return BankForMaterials()
        }

        return when {
            constructionHotspot != null -> ContinueBuilding()
            regularHotspot != null -> BuildRegularSpot()
            else -> WalkToBlueprints()
        }
    }

    override suspend fun FortBuilder.stateLoop() {
        if (addSelectedBuilding.value) {
            val buildingName = selectedBuilding.value.buildingName
            println("Adding $buildingName to queue")
            taskQueue.add(buildingName)
            println("Current queue: ${taskQueue.joinToString(", ")}")
            addSelectedBuilding.value = false
        }

        if (clearQueue.value) {
            println("Clearing queue")
            taskQueue.clear()
            currentTaskIndex = 0
            println("Queue cleared")
            clearQueue.value = false
        }

        if (debugQueueFlag.value) {
            println("DEBUG: Current queue size: ${taskQueue.size}")
            println("DEBUG: Current queue contents: ${taskQueue.joinToString(", ")}")
            println("DEBUG: Current task index: $currentTaskIndex")
            println("DEBUG: Current building: ${getCurrentBuilding()}")
            debugQueueFlag.value = false
        }

        delay(600, 800)
    }
}

class ContinueBuilding : State<FortBuilder>() {
    override suspend fun FortBuilder.checkNext(): State<FortBuilder>? {
        val hotspot = allObjects.firstOrNull { it.name() == "Optimal Construction hotspot" }
        if (hotspot == null) {
            isCurrentlyBuilding = false
            println("Building completed successfully")
            moveToNextTask()
            return CheckState()
        }
        return null
    }

    override suspend fun FortBuilder.stateLoop() {
        val hotspot = allObjects.firstOrNull { it.name() == "Optimal Construction hotspot" } ?:return

        if (!localPlayer.isMoving) {
            val distance = localPlayer.tile.getDistance(hotspot.tile)

            if (distance >= 20) {
                hasClickedBuild = false
                println("Construction spot too far away ($distance tiles), moving closer")
                walkTo(Tile.of(3308, 3553, 0).randomize(3), true)
                delay(2000, 3000)
                return
            }

            if (!hasClickedBuild && distance >= 1) {
                if (hotspot.interact("Build")) {
                    println("Interacting with construction spot")
                    hasClickedBuild = true
                    isCurrentlyBuilding = true
                    delay(
                        if (activePlayMode.value) 800 else 3000,
                        if (activePlayMode.value) 1200 else 10000
                    )
                }
            } else if (hasClickedBuild && distance > 1) {
                delay(
                    if (activePlayMode.value) 800 else 3000,
                    if (activePlayMode.value) 1200 else 10000
                )
                hasClickedBuild = false
            }
        } else {
            delay(1000, 1500)
        }
    }
}

class BuildRegularSpot : State<FortBuilder>() {
    override suspend fun FortBuilder.checkNext(): State<FortBuilder>? {
        val hotspot = allObjects.firstOrNull { it.name() == "Optimal Construction hotspot" }
        return if (hotspot == null) CheckState() else null
    }

    override suspend fun FortBuilder.stateLoop() {
        val hotspot = allObjects.firstOrNull { it.name() == "Optimal Construction hotspot" } ?:return

        if (!localPlayer.isMoving) {
            val distance = localPlayer.tile.getDistance(hotspot.tile)

            if (distance >= 20) {
                hasClickedBuild = false
                println("Regular construction spot too far away ($distance tiles), moving closer")
                walkTo(Tile.of(3308, 3553, 0).randomize(3), true)
                delay(2000, 3000)
                return
            }

            if (!hasClickedBuild && distance >= 1) {
                if (hotspot.interact("Build")) {
                    println("Found regular construction spot")
                    hasClickedBuild = true
                    delay(
                        if (activePlayMode.value) 800 else 3000,
                        if (activePlayMode.value) 1200 else 10000
                    )
                }
            } else if (hasClickedBuild && distance > 1) {
                delay(
                    if (activePlayMode.value) 800 else 3000,
                    if (activePlayMode.value) 1200 else 10000
                )
                hasClickedBuild = false
            }
        } else {
            delay(1000, 1500)
        }
    }
}

class WalkToBlueprints : State<FortBuilder>() {
    override suspend fun FortBuilder.checkNext(): State<FortBuilder>? {
        val blueprints = findClosestObject("Fort Forinthry blueprints", 60)
        return if (blueprints != null && localPlayer.tile.getDistance(blueprints.tile) <= 40) {
            CheckPlans()
        } else null
    }

    override suspend fun FortBuilder.stateLoop() {
        val blueprints = findClosestObject("Fort Forinthry blueprints", 60)
        if (blueprints != null) {
            if (localPlayer.tile.getDistance(blueprints.tile) > 20) {
                println("Walking to blueprint table")
                walkTo(Tile.of(3308, 3553, 0).randomize(3), true)
                delay(2000, 3000)
            }
        } else {
            println("Cannot find Fort Forinthry blueprints")
            delay(2000)
        }
    }
}

class CheckPlans : State<FortBuilder>() {
    override suspend fun FortBuilder.checkNext(): State<FortBuilder>? {
        if (hasSubmittedPlan) {
            isCurrentlyBuilding = true
            hasSubmittedPlan = false
            return CheckState()
        }
        return null
    }

    override suspend fun FortBuilder.stateLoop() {
        val currentBuilding = getCurrentBuilding()
        if (currentBuilding == null) {
            println("No current building to plan")
            return
        }

        if (!hasRequiredMaterials(currentBuilding)) {
            println("Missing materials for $currentBuilding")
            return
        }

        val blueprints = findClosestObject("Fort Forinthry blueprints", 60)
        if (blueprints != null && !localPlayer.isMoving) {
            if (localPlayer.tile.getDistance(blueprints.tile) <= 40) {
                if (blueprints.interact("Check plans")) {
                    println("Checking plans for $currentBuilding")
                    delay(1000, 2000)

                    if (interfaces.isOpen(1370)) {
                        continueMakeX()
                        waitThenDelayUntil(1200,5000){ !hasActiveMakeXProgress }
                        println("Started blueprint for $currentBuilding")
                        hasSubmittedPlan = true
                        delay(800, 1200)
                    } else {
                        println("Failed to start blueprint")
                        delay(500, 800)
                    }
                }
            }
        }
    }
}

class BankForMaterials : State<FortBuilder>() {
    override suspend fun FortBuilder.checkNext(): State<FortBuilder>? {
        val currentBuilding = getCurrentBuilding()
        if (currentBuilding != null && hasRequiredMaterials(currentBuilding)) {
            return CheckState()
        }
        return null
    }

    override suspend fun FortBuilder.stateLoop() {
        val currentBuilding = getCurrentBuilding() ?: return

        val requirements = buildingRequirements[currentBuilding] ?: return

        var needsBankTrip = false
        for ((materialName, requiredAmount) in requirements) {
            val inventoryCount = inventory
                .filter { it.name.contains(materialName, ignoreCase = true) }
                .sumOf { it.amount }
            if (inventoryCount < requiredAmount) {
                needsBankTrip = true
                break
            }
        }

        if (!needsBankTrip) {
            println("Already have all required materials for $currentBuilding")
            return
        }

        if (bankOpen) {
            var allMaterialsGathered = true

            for ((materialName, requiredAmount) in requirements) {
                val inventoryCount = inventory
                    .filter { it.name.contains(materialName, ignoreCase = true) }
                    .sumOf { it.amount }

                if (inventoryCount < requiredAmount) {
                    val needed = requiredAmount - inventoryCount
                    println("Withdrawing $needed $materialName")

                    if (withdrawBankItem(materialName, needed)) {
                        println("Successfully withdrew $needed $materialName")
                    } else {
                        println("Failed to withdraw $materialName")
                        allMaterialsGathered = false
                    }
                    delay(1200, 1800)
                }
            }

            if (allMaterialsGathered) {
                closeBank()
                delay(600, 800)
            }
        } else {
            if (openClosestBank()) {
                println("Opening bank")
                delay(600, 800)
            } else {
                println("Cannot find bank")
                delay(2000)
            }
        }
    }
}
