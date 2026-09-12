package com.projectx.script.impl.qb.Temporary

import org.projectx.core.game.skill.Skill
import com.projectx.script.*
import com.projectx.script.api.*

@ScriptDescription(
    name = "Forinthry Framer & Stoner",
    version = "1.0.0",
    author = "Billy",
    description = "Processes logs into planks then frames, and limestone into limestone bricks then stonewall segments in Fort Forinthry, Bank using the last preset.",
)
class FortnthryFramerStoner : StateMachineScript<FortnthryFramerStoner>(), ConfigurableScript {

    val processingMode = EnumConfigItem(
        name = "Processing Mode",
        description = "Select what to process",
        enumValues = ProcessingMode.entries.toTypedArray(),
        initialValue = ProcessingMode.LOGS_TO_FRAMES
    )

    val enableDebug = BooleanConfigItem(
        name = "Debug Mode",
        description = "Enable debug output",
        initialValue = false
    )

    val mode: ProcessingMode get() = processingMode.value
    val debug: Boolean get() = enableDebug.value

    var itemsProcessed = 0
    var currentResource = ""

    override fun getStartState(): State<FortnthryFramerStoner> = DetermineStartingState()

    enum class ProcessingMode(val description: String) {
        LOGS_TO_FRAMES("Process logs -> planks -> refined planks -> frames"),
        LIMESTONE_TO_WALLS("Process limestone -> bricks -> walls")
    }

    fun hasLogs(): Boolean = inventory.hasItem(Regex(".*[Ll]ogs$"))
    fun hasPlanks(): Boolean = inventory.hasItem(Regex(".*[Pp]lank$"))
    fun hasRefinedPlanks(): Boolean {
        val hasItems = inventory.hasItem(Regex(".*Refined planks$"))
        if (!hasItems) return false
        val count = inventory.count("Refined planks")
        return count >= 3
    }
    fun hasLimestone(): Boolean = inventory.hasItem("Limestone")
    fun hasLimestoneBricks(): Boolean = inventory.hasItem("Limestone brick")

    fun getResourceName(): String {
        return when {
            hasLogs() -> inventory.firstOrNull { it.name.matches(Regex(".*[Ll]ogs?")) }?.name ?: ""
            hasPlanks() -> inventory.firstOrNull { it.name.matches(Regex(".*[Pp]lank$")) }?.name ?: ""
            hasRefinedPlanks() -> inventory.firstOrNull { it.name.endsWith(".*Refined planks$") }?.name ?: ""
            hasLimestone() -> "Limestone"
            hasLimestoneBricks() -> "Limestone brick"
            else -> ""
        }
    }

    fun needsBanking(): Boolean {
        return when (mode) {
            ProcessingMode.LOGS_TO_FRAMES -> {
                val refinedPlanksCount = inventory.count("Refined planks")
                when {
                    hasLogs() -> false
                    hasPlanks() -> false
                    hasRefinedPlanks() -> refinedPlanksCount < 3
                    else -> true
                }
            }
            ProcessingMode.LIMESTONE_TO_WALLS -> !hasLimestone() && !hasLimestoneBricks()
        }
    }
}

class DetermineStartingState : State<FortnthryFramerStoner>() {
    override suspend fun FortnthryFramerStoner.checkNext(): State<FortnthryFramerStoner>? {
        currentResource = getResourceName()
        if (debug) println("[INIT] Starting with resource: $currentResource, mode: ${mode.description}")

        return when {
            hasLogs() -> {
                if (debug) println("[INIT] Found logs, starting processing")
                ProcessLogsToPlank()
            }
            hasPlanks() -> {
                if (debug) println("[INIT] Found planks, starting refined plank processing")
                ProcessPlanksToRefinedPlanks()
            }
            hasRefinedPlanks() -> {
                if (debug) println("[INIT] Found refined planks, starting frame construction")

                ProcessRefinedPlanksToFrames()
            }
            hasLimestone() -> {
                if (debug) println("[INIT] Found limestone, starting brick processing")
                ProcessLimestoneToBricks()
            }
            hasLimestoneBricks() -> {
                if (debug) println("[INIT] Found limestone bricks, starting wall construction")
                ProcessBricksToWalls()
            }
            needsBanking() -> {
                if (debug) println("[INIT] No resources, going to bank")
                Banking()
            }
            else -> {
                if (debug) println("[INIT] Unknown state, defaulting to banking")
                Banking()
            }
        }
    }

    override suspend fun FortnthryFramerStoner.stateLoop() {
        if (debug) println("[STATE] DetermineStartingState - Analyzing inventory")
        delay(100)
    }
}

class ProcessLogsToPlank : State<FortnthryFramerStoner>() {
    override suspend fun FortnthryFramerStoner.checkNext(): State<FortnthryFramerStoner>? {
        return when {
            !hasLogs() && hasPlanks() -> {
                if (debug) println("[TRANSITION] No more logs, have planks - switching to ProcessPlanksToRefinedPlanks")
                ProcessPlanksToRefinedPlanks()
            }
            !hasLogs() -> {
                if (debug) println("[TRANSITION] No logs - going to bank")
                Banking()
            }
            else -> null
        }
    }

    override suspend fun FortnthryFramerStoner.stateLoop() {
        if (debug) println("[STATE] ProcessLogsToPlank - Processing logs into planks")

        if (!makeXOpen) {
            val nearbyObject = findClosestObject("Sawmill")
            if (nearbyObject != null) {
                if (interactClosestObject("Process planks")) {
                    delayUntil(10000) { makeXOpen }
                    if (debug) println("[ACTION] Opened plank processing interface")
                }
            } else {
                if (debug) println("[ERROR] Could not find sawmill")
                delay(2000)
            }
            return
        }

        continueMakeX()
        waitForXPDrop(Skill.CONSTRUCTION)
        delayUntil(300000) { !hasLogs() }

        itemsProcessed++
        if (debug) println("[ACTION] Finished processing logs to planks (Batch: $itemsProcessed)")
    }
}

class ProcessPlanksToFrames : State<FortnthryFramerStoner>() {
    override suspend fun FortnthryFramerStoner.checkNext(): State<FortnthryFramerStoner>? {
        return when {
            !hasPlanks() -> {
                if (debug) println("[TRANSITION] No planks - going to bank")
                Banking()
            }
            else -> null
        }
    }

    override suspend fun FortnthryFramerStoner.stateLoop() {
        if (debug) println("[STATE] ProcessPlanksToFrames - Processing planks into frames")

        if (!makeXOpen) {
            val nearbyObject = findClosestObject("Woodworking bench")
            if (nearbyObject != null) {
                if (interactClosestObject("Construct frames")) {
                    delayUntil(10000) { makeXOpen }
                    if (debug) println("[ACTION] Opened frame construction interface")
                }
            } else {
                if (debug) println("[ERROR] Could not find construction station")
                delay(2000)
            }
            return
        }

        continueMakeX()
        waitForXPDrop(Skill.CONSTRUCTION)
        delayUntil(300000) { !hasPlanks() }

        itemsProcessed++
        if (debug) println("[ACTION] Finished processing planks to frames (Batch: $itemsProcessed)")
    }
}

class ProcessPlanksToRefinedPlanks : State<FortnthryFramerStoner>() {
    override suspend fun FortnthryFramerStoner.checkNext(): State<FortnthryFramerStoner>? {
        return when {
            !hasPlanks() && hasRefinedPlanks() -> {
                if (debug) println("[TRANSITION] No more planks, have refined planks - switching to ProcessRefinedPlanksToFrames")
                ProcessRefinedPlanksToFrames()
            }
            !hasPlanks() -> {
                if (debug) println("[TRANSITION] No planks - going to bank")
                Banking()
            }
            else -> null
        }
    }

    override suspend fun FortnthryFramerStoner.stateLoop() {
        if (debug) println("[STATE] ProcessPlanksToRefinedPlanks - Processing planks into refined planks")

        if (!makeXOpen) {
            val nearbyObject = findClosestObject("Sawmill")
            if (nearbyObject != null) {
                if (interactClosestObject("Process planks")) {
                    delayUntil(10000) { makeXOpen }
                    if (debug) println("[ACTION] Opened refined plank processing interface")
                }
            } else {
                if (debug) println("[ERROR] Could not find processing station")
                delay(2000)
            }
            return
        }

        continueMakeX()
        waitForXPDrop(Skill.CONSTRUCTION)
        delayUntil(300000) { !hasPlanks() }

        itemsProcessed++
        if (debug) println("[ACTION] Finished processing planks to refined planks (Batch: $itemsProcessed)")
    }
}

class ProcessRefinedPlanksToFrames : State<FortnthryFramerStoner>() {
    override suspend fun FortnthryFramerStoner.checkNext(): State<FortnthryFramerStoner>? {
        return when {
            !hasRefinedPlanks() -> {
                if (debug) println("[TRANSITION] No refined planks - going to bank")
                Banking()
            }
            else -> null
        }
    }

    override suspend fun FortnthryFramerStoner.stateLoop() {
        if (debug) println("[STATE] ProcessRefinedPlanksToFrames - Processing refined planks into frames")

        if (!makeXOpen) {
            val nearbyObject = findClosestObject("Woodworking bench")
            if (nearbyObject != null) {
                if (interactClosestObject("Construct frames")) {
                    delayUntil(10000) { makeXOpen }
                    if (debug) println("[ACTION] Opened frame construction interface")
                }
            } else {
                if (debug) println("[ERROR] Could not find construction station")
                delay(2000)
            }
            return
        }

        continueMakeX()
        waitForXPDrop(Skill.CONSTRUCTION)
        delayUntil(300000) { !hasRefinedPlanks() }

        itemsProcessed++
        if (debug) println("[ACTION] Finished processing refined planks to frames (Batch: $itemsProcessed)")
    }
}

class ProcessLimestoneToBricks : State<FortnthryFramerStoner>() {
    override suspend fun FortnthryFramerStoner.checkNext(): State<FortnthryFramerStoner>? {
        return when {
            !hasLimestone() && hasLimestoneBricks() -> {
                if (debug) println("[TRANSITION] No limestone, have bricks - switching to ProcessBricksToWalls")
                ProcessBricksToWalls()
            }
            !hasLimestone() -> {
                if (debug) println("[TRANSITION] No limestone - going to bank")
                Banking()
            }
            else -> null
        }
    }

    override suspend fun FortnthryFramerStoner.stateLoop() {
        if (debug) println("[STATE] ProcessLimestoneToBricks - Processing limestone into bricks")

        if (!makeXOpen) {
            val nearbyObject = findClosestObject("Stonecutter")
            if (nearbyObject != null) {
                if (interactClosestObject("Cut stone")) {
                    delayUntil(10000) { makeXOpen }
                    if (debug) println("[ACTION] Opened limestone processing interface")
                }
            } else {
                if (debug) println("[ERROR] Could not find stone cutting station")
                delay(2000)
            }
            return
        }

        continueMakeX()
        waitForXPDrop(Skill.CRAFTING)
        delayUntil(300000) { !hasLimestone() }

        itemsProcessed++
        if (debug) println("[ACTION] Finished processing limestone to bricks (Batch: $itemsProcessed)")
    }
}

class ProcessBricksToWalls : State<FortnthryFramerStoner>() {
    override suspend fun FortnthryFramerStoner.checkNext(): State<FortnthryFramerStoner>? {
        return when {
            !hasLimestoneBricks() -> {
                if (debug) println("[TRANSITION] No limestone bricks - going to bank")
                Banking()
            }
            else -> null
        }
    }

    override suspend fun FortnthryFramerStoner.stateLoop() {
        if (debug) println("[STATE] ProcessBricksToWalls - Processing limestone bricks into stonewall segments")

        if (!makeXOpen) {
            val nearbyObject = findClosestObject("Stonecutter")
            if (nearbyObject != null) {
                if (interactClosestObject("Cut stone")) {
                    delayUntil(10000) { makeXOpen }
                    if (debug) println("[ACTION] Opened stonewall construction interface")
                }
            } else {
                if (debug) println("[ERROR] Could not find stone cutting station")
                delay(2000)
            }
            return
        }

        continueMakeX()
        waitForXPDrop(Skill.CONSTRUCTION)
        delayUntil(300000) { !hasLimestoneBricks() }

        itemsProcessed++
        if (debug) println("[ACTION] Finished processing limestone bricks to stonewall segments (Batch: $itemsProcessed)")
    }
}

class Banking : State<FortnthryFramerStoner>() {
    override suspend fun FortnthryFramerStoner.checkNext(): State<FortnthryFramerStoner>? {
        return when {
            hasLogs() -> {
                if (debug) println("[TRANSITION] Have logs - switching to ProcessLogsToPlank")
                ProcessLogsToPlank()
            }
            hasPlanks() -> {
                if (debug) println("[TRANSITION] Have planks - switching to ProcessPlanksToRefinedPlanks")
                ProcessPlanksToRefinedPlanks()
            }
            hasRefinedPlanks() -> {
                if (debug) println("[TRANSITION] Have refined planks - switching to ProcessRefinedPlanksToFrames")
                ProcessRefinedPlanksToFrames()
            }
            hasLimestone() -> {
                if (debug) println("[TRANSITION] Have limestone - switching to ProcessLimestoneToBricks")
                ProcessLimestoneToBricks()
            }
            hasLimestoneBricks() -> {
                if (debug) println("[TRANSITION] Have limestone bricks - switching to ProcessBricksToWalls")
                ProcessBricksToWalls()
            }
            else -> null
        }
    }

    override suspend fun FortnthryFramerStoner.stateLoop() {
        if (debug) println("[STATE] Banking - Loading resources from bank")

        val nearbyBank = findClosestObject("Bank chest")
        if (nearbyBank != null) {
            if (interactClosestObject("Load Last Preset from")) {
                delayUntil(5000) { hasLogs() || hasPlanks() || hasLimestone() || hasLimestoneBricks() }
                if (debug) println("[ACTION] Loaded resources from bank preset")
            }
        } else {
            if (debug) println("[ERROR] Could not find bank")
            delay(2000)
        }
    }
}
