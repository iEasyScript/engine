package com.projectx.script.impl.qb.Temporary

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.location.SceneObject
import world.gregs.voidps.path.PathFinder
import com.projectx.pathfinder.WorldCollision
import world.gregs.voidps.collision.CollisionStrategies
import world.gregs.voidps.path.toTiles
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.random

@ScriptDescription(
    name = "Het's Bush Machine",
    version = "1.0.0",
    author = "Query & Billy",
    description = "Fully automated farming using state machine pattern for flowers and bush cultivation. (Legacy - use QB Activities Manager instead)",
)
class HetsBushManicheState : StateMachineScript<HetsBushManicheState>(), ConfigurableScript {
    val selectedBush = EnumConfigItem(
        name = "Bush Type",
        description = "Select which bush type to farm",
        enumValues = BUSHES.entries.toTypedArray(),
        initialValue = BUSHES.MANURE
    )

    val enableDebug = BooleanConfigItem(
        name = "Debug Mode", description = "Enable debug output", initialValue = true
    )

    val huntByLevel = BooleanConfigItem(
        name = "Farm by Level", description = "Farm bushes based on level requirement", initialValue = false
    )

    val useCompost = BooleanConfigItem(
        name = "Use Compost", description = "Automatically apply compost to bushes", initialValue = false
    )

    val bush: BUSHES
        get() = selectedBush.value

    val goByLevel: Boolean
        get() = huntByLevel.value

    val debug: Boolean
        get() = enableDebug.value

    val applyCompost: Boolean
        get() = useCompost.value

    val bankingCoord: Tile = Tile.of(3270, 3167, 0)
    val bankingArea: Area = Area.Circular(bankingCoord, 10.0)
    var itemsHarvested = 0
    var animationDelay = 0

    val compostVarbits = mapOf(
        BUSHES.ROSE to 50832,
        BUSHES.IRIS to 50833,
        BUSHES.HYDRANGEA to 50834,
        BUSHES.HOLLYHOCK to 50835
    )

    override fun getStartState() = FarmingBush()

    enum class BUSHES(
        val level: Int,
        val displayName: String,
        val action: String,
        val location: Tile,
        val area: Area
    ) {
        MANURE(1, "Manure mound", "Turn", Tile.of(2659, 3364, 0), Area.Circular(Tile.of(2659, 3364, 0), 10.0)),
        ROSE(30, "Rose bush", "Cultivate", Tile.of(3357, 3257, 0), Area.Circular(Tile.of(3356, 3257, 0), 10.0)),
        IRIS(50, "Iris bush", "Cultivate", Tile.of(3386, 3256, 0), Area.Circular(Tile.of(3386, 3256, 0), 10.0)),
        HYDRANGEA(70, "Hydrangea bush", "Cultivate", Tile.of(3387, 3206, 0), Area.Circular(Tile.of(3387, 3206, 0), 10.0)),
        HOLLYHOCK(90, "Hollyhock bush", "Cultivate", Tile.of(3353, 3217, 0), Area.Circular(Tile.of(3353, 3217, 0), 10.0));

        companion object {
            private val levelMap = entries.associateBy(BUSHES::level)
            fun fromLevel(level: Int): BUSHES? {
                return levelMap[level]
            }

            fun getBestBushForLevel(currentLevel: Int): BUSHES {
                return entries.filter { it.level <= currentLevel }.maxByOrNull { it.level } ?: MANURE
            }
        }
    }

    override fun onEvent(event: Event) {
        super.onEvent(event)
        if (event is Chat && debug) {
            println(event.message)
        }
    }
}

class FarmingBush : State<HetsBushManicheState>() {
    override suspend fun HetsBushManicheState.checkNext(): State<HetsBushManicheState>? {
        if (debug) println("[TRANSITION CHECK] FarmingBush - checking next state...")

        if (inventory.isFull) {
            if (debug) println("[TRANSITION] Inventory is full, switching to Banking")
            return FarmingBanking()
        }

        val currentDistance = bush.location.getDistance(localPlayer.tile)
        if (debug) println("[LOCATION CHECK] Distance to ${bush.displayName}: $currentDistance tiles")

        if (currentDistance >= 10) {
            if (debug) println("[TRANSITION] Too far from farming location, switching to WalkingToFarm")
            return WalkingToFarm()
        }

        if (debug) println("[TRANSITION CHECK] Staying in FarmingBush state")
        return null
    }

    override suspend fun HetsBushManicheState.stateLoop() {
        if (debug) {
            println("=== FARMING STATE: FarmingBush ===")
            println("[STATUS] Current bush: ${bush.displayName} (Level ${bush.level})")
            println("[STATUS] Player location: ${localPlayer.tile}")
            println("[STATUS] Target location: ${bush.location}")
            println("[STATUS] Inventory full: ${inventory.isFull}")
            println("[STATUS] Items harvested this session: $itemsHarvested")
        }

        if (goByLevel) {
            val farmingLevel = getRealLevel(Skill.FARMING)
            val bestBush = HetsBushManicheState.BUSHES.getBestBushForLevel(farmingLevel)
            if (debug) println("[LEVEL CHECK] Current farming level: $farmingLevel, best bush: ${bestBush.displayName}")

            if (bestBush != bush) {
                if (debug) println("[LEVEL UPDATE] Switching from ${bush.displayName} to ${bestBush.displayName}")
                selectedBush.value = bestBush
                return
            }
        }

        if (bush == HetsBushManicheState.BUSHES.MANURE) {
            if (debug) println("[FARMING] Handling manure farming")
            handleManureFarming()
            return
        }

        if (debug) println("[FARMING] Handling bush farming for ${bush.displayName}")
        handleBushFarming()
    }

    private suspend fun HetsBushManicheState.handleManureFarming() {
        if (debug) println("[MANURE] Checking player animation state...")

        if (localPlayer.isAnimating) {
            if (debug) println("[MANURE] Player is animating, resetting delay counter")
            animationDelay = 0
            return
        } else {
            animationDelay++
            if (debug) println("[MANURE] Player not animating, delay counter: $animationDelay")
            if (animationDelay < 3) {
                if (debug) println("[MANURE] Waiting for animation delay (${animationDelay}/3)")
                delay(random(600, 1200))
                return
            }
        }

        if (debug) println("[MANURE] Searching for manure objects...")
        val manureObject = findClosestObject { it.name().contains(bush.displayName, ignoreCase = true) }

        if (manureObject == null) {
            if (debug) println("[ERROR] No manure object found near player")
            return
        }

        if (debug) {
            println("[MANURE] Found manure object: ${manureObject.name()}")
            println("[MANURE] Object location: ${manureObject.tile}")
        }

        if (debug) println("[ACTION] Interacting with ${bush.displayName} using action '${bush.action}'")
        manureObject.interact(bush.action)
        delay(random(600, 1200))
    }

    private suspend fun HetsBushManicheState.handleBushFarming() {
        if (debug) println("[BUSH] Starting bush farming routine...")

        spotAnims.forEach { println(it.id) }

        if (debug) println("[PEST CHECK] Searching for pesty scarabs...")
        val pestyScarab = findClosestNPC("Pesty scarab")
        if (pestyScarab != null) {
            if (debug) {
                println("[PEST FOUND] Pesty scarab detected at ${pestyScarab.tile}")
                println("[ACTION] Shooing pesty scarab")
            }
            pestyScarab.interact("Shoo")
            delay(random(1800, 2400))
            return
        } else {
            if (debug) println("[PEST CHECK] No pesty scarabs found")
        }


        val nutritiousGas = spotAnims.firstOrNull { it.id==7620  }
        if (nutritiousGas != null) {
            val cultivateBush = findClosestObject {
                it.name().contains(bush.displayName, ignoreCase = true) && it.hasOption(bush.action)
            }
            cultivateBush!!.interact(bush.action)
            delay(random(600, 1200))
            return
        }

        if (debug) println("[HARVEST CHECK] Searching for harvestable bushes...")
        val grownBush = findClosestObject {
            it.name().contains(bush.displayName, ignoreCase = true) && it.hasOption("Harvest")
        }

        if (grownBush != null) {
            if (debug) {
                println("[HARVEST FOUND] Harvestable bush found: ${grownBush.name()}")
                println("[HARVEST FOUND] Bush location: ${grownBush.tile}")
            }
            if (debug) println("[ACTION] Harvesting grown bush")
            grownBush.interact("Harvest")
            itemsHarvested++
            if (debug) println("[HARVEST] Total items harvested: $itemsHarvested")
            delay(random(600, 1200))
            return
        } else {
            if (debug) println("[HARVEST CHECK] No harvestable bushes found")
        }

        if (debug) println("[ANIMATION] Checking player animation state...")
        if (localPlayer.isAnimating) {
            if (debug) println("[ANIMATION] Player is animating, resetting delay counter")
            animationDelay = 0
            return
        } else {
            animationDelay++
            if (debug) println("[ANIMATION] Player not animating, delay counter: $animationDelay")
            if (animationDelay < 3) {
                if (debug) println("[ANIMATION] Waiting for animation delay (${animationDelay}/3)")
                delay(random(600, 1200))
                return
            }
        }

        if (debug) println("[CULTIVATE CHECK] Searching for bushes to cultivate...")
        val cultivateBush = findClosestObject {
            it.name().contains(bush.displayName, ignoreCase = true) && it.hasOption(bush.action)
        }

        if (cultivateBush != null) {
            if (debug) {
                println("[CULTIVATE FOUND] Bush found for cultivation: ${cultivateBush.name()}")
                println("[CULTIVATE FOUND] Bush location: ${cultivateBush.tile}")
            }

            if (applyCompost) {
                if (debug) println("[COMPOST CHECK] Checking if compost should be applied...")
                if (shouldApplyCompost(cultivateBush)) {
                    if (debug) println("[COMPOST] Applying compost before cultivation")
                    applyCompostToBush(cultivateBush)
                    return
                } else {
                    if (debug) println("[COMPOST] No compost needed or available")
                }
            }

            if (debug) println("[ACTION] Cultivating bush with action '${bush.action}'")
            cultivateBush.interact(bush.action)
            delay(random(600, 1200))
        } else {
            if (debug) println("[CULTIVATE CHECK] No suitable bush found for cultivation")

            if (debug) println("[DEBUG] No nearby objects found for cultivation")
        }
    }

    private fun HetsBushManicheState.shouldApplyCompost(bushObject: SceneObject): Boolean {
        if (debug) println("[COMPOST CHECK] Evaluating compost application for ${bushObject.name()}")

        val varbitId = compostVarbits[bush]
        if (debug) println("[COMPOST CHECK] Varbit ID for ${bush.displayName}: $varbitId")

        if (varbitId == null) {
            if (debug) println("[COMPOST CHECK] No varbit found for this bush type")
            return false
        }

        val isComposted = varps.getVarBit(varbitId) == 1
        val isBare = bushObject.name().contains("bare", ignoreCase = true)
        val hasCompost = hasCompostInInventory()

        if (debug) {
            println("[COMPOST CHECK] Is already composted: $isComposted")
            println("[COMPOST CHECK] Is bare bush: $isBare")
            println("[COMPOST CHECK] Has compost in inventory: $hasCompost")
        }

        val shouldApply = !isComposted && isBare && hasCompost
        if (debug) println("[COMPOST CHECK] Should apply compost: $shouldApply")
        
        return shouldApply
    }

    private fun HetsBushManicheState.hasCompostInInventory(): Boolean {
        val hasCompost = inventory.hasItem("Compost")
        val hasSuperCompost = inventory.hasItem("Supercompost")
        val hasUltraCompost = inventory.hasItem("Ultracompost")
        
        if (debug) {
            println("[INVENTORY] Compost check - Regular: $hasCompost, Super: $hasSuperCompost, Ultra: $hasUltraCompost")
        }
        
        return hasCompost || hasSuperCompost || hasUltraCompost
    }

    private suspend fun HetsBushManicheState.applyCompostToBush(bushObject: SceneObject) {
        if (debug) println("[COMPOST] Starting compost application process...")
        
        val compost = inventory.getItem("Compost") ?: inventory.getItem("Supercompost") ?: inventory.getItem("Ultracompost")

        if (compost != null) {
            if (debug) {
                println("[COMPOST] Found compost item: ${compost.name}")
                println("[COMPOST] Applying ${compost.name} to ${bushObject.name()}")
            }
            
            compost.click("Use")
            delay(random(300, 600))
            bushObject.interact("Use")
            delay(random(600, 1200))
            
            if (debug) println("[COMPOST] Compost application completed")
        } else {
            if (debug) println("[ERROR] No compost found in inventory despite earlier check")
        }
    }
}

class WalkingToFarm : State<HetsBushManicheState>() {
    override suspend fun HetsBushManicheState.checkNext(): State<HetsBushManicheState>? {
        if (debug) println("[TRANSITION CHECK] WalkingToFarm - checking if arrived...")
        
        val isInArea = bush.area.contains(localPlayer.tile)
        val distance = bush.location.getDistance(localPlayer.tile)
        
        if (debug) {
            println("[LOCATION] Player at: ${localPlayer.tile}")
            println("[LOCATION] Target: ${bush.location}")
            println("[LOCATION] Distance: $distance")
            println("[LOCATION] In target area: $isInArea")
        }
        
        if (isInArea) {
            if (debug) println("[TRANSITION] Arrived at farming location, switching to FarmingBush")
            return FarmingBush()
        }
        
        return null
    }

    override suspend fun HetsBushManicheState.stateLoop() {
        if (debug) {
            println("=== FARMING STATE: WalkingToFarm ===")
            println("[WALKING] Target: ${bush.displayName} at ${bush.location}")
            println("[WALKING] Current location: ${localPlayer.tile}")
            println("[WALKING] Distance to target: ${bush.location.getDistance(localPlayer.tile)}")
        }

        if (debug) println("[PATHFINDING] Calculating route to farming location...")
        val route = PathFinder(
            flags = WorldCollision.allFlags,
            searchMapSize = 1024,
            useRouteBlockerFlags = true,
            moveNear = false
        ).findPath(
            localPlayer.tile.x,
            localPlayer.tile.y,
            bush.location.x -1,
            bush.location.y,
            localPlayer.tile.plane,
            collision = CollisionStrategies.NORMAL,
            srcSize = 2,
            destWidth = 1,
            destHeight = 1
        )

        if (route.failed) {
            if (debug) {
                println("[ERROR] Route calculation failed!")
                println("[FALLBACK] Using Al Kharid lodestone as backup")
            }
            useLodestone(Lodestone.AL_KHARID)
            delay(random(600, 1800))
            return
        }

        val tiles = route.toTiles()
        if (debug) println("[PATHFINDING] Route calculated with ${tiles.size} tiles")

        tiles.forEachIndexed { index, tile ->
            val distance = tile.getDistance(localPlayer.tile)
            if (debug) println("[WALKING] Step ${index + 1}/${tiles.size}: Moving to $tile (distance: $distance)")
            
            if (distance > 7) {
                if (debug) println("[WALKING] Walking to tile $tile")
                walkTo(tile, true)
                
                delayUntil(5000) { tile.getDistance(localPlayer.tile) < 4 }
              
            } else {
                if (debug) println("[WALKING] Skipping tile $tile (too close: $distance)")
            }
        }
        
        if (debug) println("[WALKING] Completed walking routine")
    }
}

class FarmingBanking : State<HetsBushManicheState>() {
    override suspend fun HetsBushManicheState.checkNext(): State<HetsBushManicheState>? {
        if (debug) println("[TRANSITION CHECK] FarmingBanking - checking next state...")
        
        val inBankArea = bankingArea.contains(localPlayer.tile)
        val inventoryFull = inventory.isFull
        val hasCompost = hasCompostInInventory()
        
        if (debug) {
            println("[BANKING CHECK] In banking area: $inBankArea")
            println("[BANKING CHECK] Inventory full: $inventoryFull")
            println("[BANKING CHECK] Has compost: $hasCompost")
            println("[BANKING CHECK] Apply compost enabled: $applyCompost")
        }

        if (!inBankArea) {
            if (debug) println("[TRANSITION] Not in banking area, switching to WalkingToBank")
            return WalkingToBank()
        }

        if (!inventoryFull && (!applyCompost || hasCompost)) {
            if (debug) println("[TRANSITION] Banking complete, returning to FarmingBush")
            return FarmingBush()
        }

        return null
    }

    override suspend fun HetsBushManicheState.stateLoop() {
        if (debug) {
            println("=== FARMING STATE: Banking ===")
            println("[BANKING] Current location: ${localPlayer.tile}")
            println("[BANKING] Banking area center: $bankingCoord")
            println("[BANKING] Inventory full: ${inventory.isFull}")
        }

        if (debug) println("[INFO] Banking functionality is not implemented (commented out)")

        delay(2000)
    }

    private fun HetsBushManicheState.hasCompostInInventory(): Boolean {
        val hasCompost = inventory.hasItem("Compost")
        val hasSuperCompost = inventory.hasItem("Supercompost") 
        val hasUltraCompost = inventory.hasItem("Ultracompost")
        
        if (debug) {
            println("[BANKING INVENTORY] Compost check - Regular: $hasCompost, Super: $hasSuperCompost, Ultra: $hasUltraCompost")
        }
        
        return hasCompost || hasSuperCompost || hasUltraCompost
    }
}

class WalkingToBank : State<HetsBushManicheState>() {
    override suspend fun HetsBushManicheState.checkNext(): State<HetsBushManicheState>? {
        if (debug) println("[TRANSITION CHECK] WalkingToBank - checking if arrived...")
        
        val inBankArea = bankingArea.contains(localPlayer.tile)
        val distance = bankingCoord.getDistance(localPlayer.tile)
        
        if (debug) {
            println("[BANK LOCATION] Player at: ${localPlayer.tile}")
            println("[BANK LOCATION] Bank center: $bankingCoord") 
            println("[BANK LOCATION] Distance: $distance")
            println("[BANK LOCATION] In banking area: $inBankArea")
        }
        
        if (inBankArea) {
            if (debug) println("[TRANSITION] Arrived at banking area, switching to FarmingBanking")
            return FarmingBanking()
        }
        
        return null
    }

    override suspend fun HetsBushManicheState.stateLoop() {
        if (debug) {
            println("=== FARMING STATE: WalkingToBank ===")
            println("[BANK WALKING] Target: Banking area at $bankingCoord")
            println("[BANK WALKING] Current location: ${localPlayer.tile}")
            println("[BANK WALKING] Distance to bank: ${bankingCoord.getDistance(localPlayer.tile)}")
        }

        if (debug) println("[BANK PATHFINDING] Calculating route to banking area...")
        val route = PathFinder(
            flags = WorldCollision.allFlags,
            searchMapSize = 512,
            useRouteBlockerFlags = true,
            moveNear = false
        ).findPath(
            localPlayer.tile.x,
            localPlayer.tile.y,
            bankingCoord.x,
            bankingCoord.y,
            localPlayer.tile.plane,
            collision = CollisionStrategies.NORMAL,
            srcSize = 2,
            destWidth = 1,
            destHeight = 1
        )

        if (route.failed) {
            if (debug) {
                println("[ERROR] Route to bank failed!")
                println("[FALLBACK] Using Al Kharid lodestone as backup")
            }
            useLodestone(Lodestone.AL_KHARID)
            delay(random(600, 1800))
            return
        }

        val tiles = route.toTiles()
        if (debug) println("[BANK PATHFINDING] Route calculated with ${tiles.size} tiles")

        tiles.forEachIndexed { index, tile ->
            val distance = tile.getDistance(localPlayer.tile)
            if (debug) println("[BANK WALKING] Step ${index + 1}/${tiles.size}: Moving to $tile (distance: $distance)")
            
            if (distance > 7) {
                if (debug) println("[BANK WALKING] Walking to tile $tile")
                walkTo(tile, true)
                
               delayUntil(5000) { tile.getDistance(localPlayer.tile) < 4 }
                
            } else {
                if (debug) println("[BANK WALKING] Skipping tile $tile (too close: $distance)")
            }
        }
        
        if (debug) println("[BANK WALKING] Completed walking routine")
    }
}

class FarmingIdle : State<HetsBushManicheState>() {
    override suspend fun HetsBushManicheState.checkNext(): State<HetsBushManicheState> {
        if (debug) println("[TRANSITION CHECK] FarmingIdle - returning to farming")
        return FarmingBush()
    }

    override suspend fun HetsBushManicheState.stateLoop() {
        if (debug) {
            println("=== FARMING STATE: Idle ===")
            println("[IDLE] Waiting in idle state...")
        }
        delay(1000)
    }
}
