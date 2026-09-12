package com.projectx.script.impl.qb.Temporary

import org.projectx.core.game.skill.Skill
import com.projectx.game.interfaces.Bank
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import kotlin.random.Random

@ScriptDescription(
    name = "Shattered Worlds",
    version = "1.0.0",
    author = "QB",
    description = "Automated Shattered Worlds script with task system support",
)
class ShatteredWorldsScript : StateMachineScript<ShatteredWorldsScript>(), ConfigurableScript {
    val selectedWorldLevel = EnumConfigItem(
        name = "World Level",
        description = "Select which world level range to complete",
        enumValues = WorldLevel.entries.toTypedArray(),
        initialValue = WorldLevel.WORLDS_1_5
    )

    val enableTaskSystem = BooleanConfigItem(
        name = "Use Task System",
        description = "Enable task system to train specific combat stats to target levels",
        initialValue = false
    )

    val loadBankPresetConfig = BooleanConfigItem(
        name = "Load Bank Preset",
        description = "Load bank preset before entering Shattered Worlds",
        initialValue = false
    )

    val selectedBankPresetConfig = EnumConfigItem(
        name = "Bank Preset",
        description = "Select which bank preset to load",
        enumValues = BankPreset.entries.toTypedArray(),
        initialValue = BankPreset.PRESET_1
    )

    val enableFeedMe = BooleanConfigItem(
        name = "Feed Me",
        description = "Enable Feed Me option when entering Shattered Worlds",
        initialValue = false
    )

    val enableDebug = BooleanConfigItem(
        name = "Debug Mode",
        description = "Enable debug output",
        initialValue = true
    )

    val selectedWorld: Int get() = selectedWorldLevel.value.ordinal
    var usingTaskSystem: Boolean
        get() = enableTaskSystem.value
        set(value) { enableTaskSystem.value = value }

    var loadBankPreset: Boolean
        get() = loadBankPresetConfig.value
        set(value) { loadBankPresetConfig.value = value }

    val selectedBankPreset: Int get() = selectedBankPresetConfig.value.ordinal

    var feedMe: Boolean
        get() = enableFeedMe.value
        set(value) { enableFeedMe.value = value }

    val debug: Boolean get() = enableDebug.value

    enum class WorldLevel(val displayName: String) {
        WORLDS_1_5("Worlds 1-5"),
        WORLDS_6_10("Worlds 6-10"),
        WORLDS_11_15("Worlds 11-15"),
        WORLDS_16_20("Worlds 16-20"),
        WORLDS_21_25("Worlds 21-25"),
        WORLDS_26_30("Worlds 26-30"),
        WORLDS_31_35("Worlds 31-35"),
        WORLDS_36_40("Worlds 36-40"),
        WORLDS_41_45("Worlds 41-45"),
        WORLDS_46_50("Worlds 46-50"),
        WORLDS_51_55("Worlds 51-55"),
        WORLDS_56_60("Worlds 56-60"),
        WORLDS_61_65("Worlds 61-65"),
        WORLDS_66_70("Worlds 66-70"),
        WORLDS_71_75("Worlds 71-75"),
        WORLDS_76_80("Worlds 76-80"),
        WORLDS_81_85("Worlds 81-85"),
        WORLDS_86_90("Worlds 86-90");

        override fun toString(): String = displayName
    }

    enum class BankPreset(val displayName: String) {
        PRESET_1("1"), PRESET_2("2"), PRESET_3("3"), PRESET_4("4"), PRESET_5("5"),
        PRESET_6("6"), PRESET_7("7"), PRESET_8("8"), PRESET_9("9"), PRESET_10("10"),
        PRESET_11("11"), PRESET_12("12"), PRESET_13("13"), PRESET_14("14"), PRESET_15("15"),
        PRESET_16("16"), PRESET_17("17"), PRESET_18("18");

        override fun toString(): String = displayName
    }

    enum class CombatStyle(val displayName: String) {
        MELEE("Melee"),
        RANGED("Ranged"),
        MAGIC("Magic"),
        NECROMANCY("Necromancy");

        override fun toString(): String = displayName
    }

    enum class CombatSkill(val displayName: String) {
        CONSTITUTION("Constitution"),
        ATTACK("Attack"),
        STRENGTH("Strength"),
        DEFENSE("Defense"),
        RANGED("Ranged"),
        MAGIC("Magic"),
        NECROMANCY("Necromancy");

        override fun toString(): String = displayName
    }

    enum class BotState {
        IDLE,
        PERFORMINGTASK
    }

    enum class ShatteredState {
        INSIDEWORLD,
        OUTSIDEWORLD,
        ACCEPTINGANIMA
    }

    private var scriptState = BotState.IDLE
    private var shatteredState = ShatteredState.OUTSIDEWORLD

    val worldLevels = WorldLevel.entries.map { it.displayName }.toTypedArray()
    val bankPresets = BankPreset.entries.map { it.displayName }.toTypedArray()
    val combatStyles = CombatStyle.entries.map { it.displayName }.toTypedArray()
    val combatSkills = CombatSkill.entries.map { it.displayName }.toTypedArray()

    var taskAmount = 0
    val taskContainer = mutableMapOf<String, MutableMap<String, Int>>()

    var completeCurrentWorld = false
    var chestEmpty = false
    private val random = Random.Default
    
    override fun getStartState(): State<ShatteredWorldsScript> = IdleState()
    
    override fun onEvent(event: Event) {
        super.onEvent(event)
        when (event) {
            is Chat -> {
                val message = event.message
                when {
                    message.contains("Objective complete! Proceed to the exit portal to advance.") ||
                    message.contains("You now have the 3 required portal repair kits to fix the portal!") -> {
                        completeCurrentWorld = true
                        println("[DEBUG] Chat event: Objective completed, setting completeCurrentWorld to true")
                    }
                    message.contains("This chest is empty. Play Shattered Worlds and your rewards will be deposited here.") -> {
                        chestEmpty = true
                        println("[DEBUG] Chat event: Chest is empty")
                    }
                }
            }
        }
    }
    
    fun getRelevantTaskSetting(): Map<String, Int> {
        val container = mutableMapOf<String, Int>()
        println("[DEBUG] Fetching relevant task settings")
        
        for (i in 1..taskAmount) {
            var taskDone = false
            taskContainer["Task$i"]?.let { task ->
                val skill = combatSkills[task["Selected Combat Skill"] ?: 0]
                val targetLevel = task["Selected Level"] ?: 1
                println("[DEBUG] Checking task $i: Skill=$skill, TargetLevel=$targetLevel")
                
                taskDone = when (skill) {
                    "Constitution" -> getCurrentLevel(Skill.CONSTITUTION) >= targetLevel
                    "Attack" -> getCurrentLevel(Skill.ATTACK) >= targetLevel
                    "Strength" -> getCurrentLevel(Skill.STRENGTH) >= targetLevel
                    "Defense" -> getCurrentLevel(Skill.DEFENSE) >= targetLevel
                    "Ranged" -> getCurrentLevel(Skill.RANGED) >= targetLevel
                    "Magic" -> getCurrentLevel(Skill.MAGIC) >= targetLevel
                    "Necromancy" -> getCurrentLevel(Skill.NECROMANCY) >= targetLevel
                    else -> false
                }
                
                if (!taskDone) {
                    container["Selected Preset"] = task["Selected Preset"] ?: 0
                    container["Selected Combat Skill"] = task["Selected Combat Skill"] ?: 0
                    container["Skill Level"] = targetLevel
                    container["Selected Combat Style"] = task["Selected Combat Style"] ?: 0
                    println("[DEBUG] Task $i not done, added to container: $container")
                    return container
                }
            }
        }
        
        return container
    }
    
    fun isOutsideShattered(): Boolean {
        val quartermaster = findClosestNPC("Abyssal Knight Quartermaster" )
        val portal = findClosestObject( "Shattered Worlds portal" )
        val challengerPortal = findClosestObject( "Shattered Worlds challenger portal" )
        
        val result = quartermaster != null && portal != null && challengerPortal != null
        println("[DEBUG] isOutsideShattered: $result")
        return result
    }
    
    fun isInsideShattered(): Boolean {
        val result = interfaces.isOpen(1866)
        println("[DEBUG] isInsideShattered: $result")
        return result
    }
    
    fun isAnimaAcceptanceArea(): Boolean {
        val crackedChest = findClosestObject( "Cracked chest" )
        val bankChest = findClosestObject( "Bank chest" )
        val bluePortal = findClosestObject( "Blue Portal" )
        val redPortal = findClosestObject( "Red Portal" )
        
        val result = crackedChest != null && bankChest != null && bluePortal != null && redPortal != null
        println("[DEBUG] isAnimaAcceptanceArea: $result")
        return result
    }
    
    suspend fun checkHealth() {
        localPlayer
        val healthRatio = healthCurrent / healthMax
        println("[DEBUG] Player health ratio: $healthRatio")
        
        if (healthRatio <= 0.3) {
            println("[DEBUG] Health low, eating food")
            eatFood()
        }
    }
    
    private suspend fun eatFood() {
        val foodItem = inventory.find { it.name.contains("food") || it.name.contains("Fish") }
        foodItem?.let {
            foodItem.click( "Eat")
            println("[DEBUG] Ate food item")
            delay(1000)
        } ?: println("[DEBUG] No food item found to eat")
    }
}

class IdleState : State<ShatteredWorldsScript>() {
    override suspend fun ShatteredWorldsScript.checkNext(): State<ShatteredWorldsScript>? {
        return when {
            isOutsideShattered() -> OutsideWorldState()
            isAnimaAcceptanceArea() -> AcceptingAnimaState()
            isInsideShattered() -> InsideWorldState()
            else -> null
        }
    }
    
    override suspend fun ShatteredWorldsScript.stateLoop() {
        println("[SCRIPTSTATE] Current state: IDLE")
        delay(1000)
    }
}

class OutsideWorldState : State<ShatteredWorldsScript>() {
    override suspend fun ShatteredWorldsScript.checkNext(): State<ShatteredWorldsScript>? {
        return when {
            isAnimaAcceptanceArea() -> AcceptingAnimaState()
            isInsideShattered() -> InsideWorldState()
            !isOutsideShattered() -> IdleState()
            else -> null
        }
    }
    
    override suspend fun ShatteredWorldsScript.stateLoop() {
        println("[SCRIPTSTATE] Current state: OUTSIDEWORLD")
        
        if (loadBankPreset) {
            val banker = findClosestNPC(50){ it.name == "Banker" }
            banker?.let {
                if (walkTo(it.tile, true)) {
                    delay(1000)
                    it.interact( "Bank")
                    delay(2000)
                    if (interfaces.isOpen(Bank.BANK_INTERFACE_ID)) {
                        selectBankPreset()
                    }
                }
            }
        }
        
        val portal = findClosestObject(50) { it.name() == "Shattered Worlds portal" }
        portal?.let {
            if (walkTo(it.tile, true)) {
                delay(1000)
                it.interact( "Enter")
                delay(5000)
                
                if (feedMe) {
                    delay(300)
                }
                
                selectWorld()
                delay(2000)
            }
        }
    }
    
    private suspend fun ShatteredWorldsScript.selectBankPreset() {
        val selected = if (usingTaskSystem) {
            val data = getRelevantTaskSetting()
            data["Selected Preset"] ?: selectedBankPreset
        } else {
            selectedBankPreset
        }
        
        println("[DEBUG] Selecting bank preset: $selected")
        Bank.doBankAction(selected + 1)
        delay(1000)
    }
    
    private suspend fun ShatteredWorldsScript.selectWorld() {
        println("[DEBUG] Selecting world: ${worldLevels[selectedWorld]}")
        delay(300)
    }
}

class InsideWorldState : State<ShatteredWorldsScript>() {
    override suspend fun ShatteredWorldsScript.checkNext(): State<ShatteredWorldsScript>? {
        return when {
            isAnimaAcceptanceArea() -> AcceptingAnimaState()
            !isInsideShattered() -> IdleState()
            else -> null
        }
    }
    
    override suspend fun ShatteredWorldsScript.stateLoop() {
        println("[SCRIPTSTATE] Current state: INSIDEWORLD")
        
        val enemy = findClosestNPC(50) { it.hasOption("Attack") }
        val brokenPortal = findClosestObject(50) { it.name() == "Portal (broken)" }
        val continuePortal = findClosestObject(50) { it.name() == "Portal" && it.hasOption("Continue") }
        val enemies = findClosestNPC(50) { it.hasOption("Attack") }
        
        println("[DEBUG] Inside handleInsideWorld, enemy=${enemy != null}, brokenPortal=${brokenPortal != null}, continuePortal=${continuePortal != null}")
        
        checkForEmptySlot()
        checkForWearable()
        checkHealth()

        when {
            enemy != null && !completeCurrentWorld -> {
                println("[DEBUG] Engaging in combat with enemy")
                handleCombat(enemy)
            }
            !enemies!!.exists() && !completeCurrentWorld -> {
                val homePortal = findClosestObject(50) { it.name() == "Portal" && it.hasOption("Exit") }
                homePortal?.let { handleExitPortal(it) }
            }
            brokenPortal != null && completeCurrentWorld -> {
                println("[DEBUG] Repairing broken portal")
                repairPortal(brokenPortal)
            }
            continuePortal != null && completeCurrentWorld -> {
                println("[DEBUG] Continuing through portal")
                continueThroughPortal(continuePortal)
            }
        }
    }
    
    private suspend fun ShatteredWorldsScript.handleCombat(enemy: NPC) {
        println("[DEBUG] Handling combat with enemy")
        val player = localPlayer
        val currenttarget = npcs[player.interactionSid]

        if (currenttarget != null && inCombat) {
            checkHealth()
            if (player.animationId == -1) {
                currenttarget.interact( "Attack")
                delay(1000)
            }
        } else {
            currenttarget?.interact( "Attack")
            println("[DEBUG] Attacked enemy")
            delay(1000)
        }
    }
    
    private suspend fun ShatteredWorldsScript.repairPortal(portal: SceneObject) {
        if (completeCurrentWorld) {
            val player = localPlayer
            val distance = portal.tile.getDistance(player.tile)
            
            println("[DEBUG] Repairing portal, distance: $distance")
            if (distance >= 15.0) {
                walkTo(portal.tile, true)
                delay(3000)
            }
            
            portal.interact("Repair")
            println("[DEBUG] Interacted with portal: Repair")
            delay(5000)
        }
    }
    
    private suspend fun ShatteredWorldsScript.continueThroughPortal(portal: SceneObject) {
        if (completeCurrentWorld) {
            val player = localPlayer
            val distance = portal.tile.getDistance(player.tile)
            
            println("[DEBUG] Continuing through portal, distance: $distance")
            if (distance >= 15.0) {
                walkTo(portal.tile, true)
                delay(3000)
            }
            
            portal.interact( "Continue")
            println("[DEBUG] Interacted with portal: Continue")
            delay(5000)
            
            completeCurrentWorld = false
        }
    }
    
    private suspend fun ShatteredWorldsScript.handleExitPortal(portal: SceneObject) {
        println("[DEBUG] No enemies, moving to exit portal")
        walkTo(portal.tile, true)
        delay(3000)
        
        portal.interact("Exit")
        println("[DEBUG] Exited through portal")
        delay(3000)
        
        completeCurrentWorld = false
    }
    
    private suspend fun ShatteredWorldsScript.checkForEmptySlot() {
        if (inventory.isFull) {
            val foodItem = inventory.find { it.click("Eat") }
            foodItem?.let {
                println("[DEBUG] Backpack full, eating food to free slot")
                while (inventory.isFull) {
                    foodItem.click( "Eat")
                    delay(2000)
                }
            }
        }
    }
    
    private suspend fun ShatteredWorldsScript.checkForWearable() {
        val wearableItem = inventory.find { it.click("Wear") || it.click("Wield") }
        wearableItem?.let {
            val action = if (it.click("Wear")) "Wear" else "Wield"
            wearableItem.click(action)
            println("[DEBUG] Equipped item: $action")
            delay(1000)
        }
    }
}

class AcceptingAnimaState : State<ShatteredWorldsScript>() {
    override suspend fun ShatteredWorldsScript.checkNext(): State<ShatteredWorldsScript>? {
        return when {
            isOutsideShattered() -> OutsideWorldState()
            isInsideShattered() -> InsideWorldState()
            !isAnimaAcceptanceArea() -> IdleState()
            else -> null
        }
    }
    
    override suspend fun ShatteredWorldsScript.stateLoop() {
        println("[SCRIPTSTATE] Current state: ACCEPTINGANIMA")

        completeCurrentWorld = false
        
        val crackedChest = findClosestObject(50) { it.name() == "Cracked chest" }
        val redPortal = findClosestObject(50) { it.name() == "Red Portal" }
        
        println("[DEBUG] Cracked chest: ${crackedChest != null}, Red portal: ${redPortal != null}")
        
        if (crackedChest != null && redPortal != null) {
            crackedChest.interact( "Open")
            println("[DEBUG] Opened cracked chest")
            delay(3000)
            
            chestEmpty = false
            
            if (loadBankPreset) {
                val bankChest = findClosestObject(50) { it.name() == "Bank chest" }
                bankChest?.let {
                    bankChest.interact("Bank")
                    delay(2000)
                    if (interfaces.isOpen(Bank.BANK_INTERFACE_ID)) {
                        selectBankPreset()
                    }
                }
            }
            
            redPortal.interact("Return to Shattered Worlds")
            println("[DEBUG] Returning to Shattered Worlds")
            delay(5000)
            
            if (feedMe) {
                delay(300)
            }
            
            selectWorld()
            delay(5000)
        }
    }
    
    private suspend fun ShatteredWorldsScript.selectBankPreset() {
        val selected = if (usingTaskSystem) {
            val data = getRelevantTaskSetting()
            data["Selected Preset"] ?: selectedBankPreset
        } else {
            selectedBankPreset
        }
        
        println("[DEBUG] Selecting bank preset: $selected")
        Bank.doBankAction(selected + 1)
        delay(1000)
    }
    
    private suspend fun ShatteredWorldsScript.selectWorld() {
        println("[DEBUG] Selecting world: ${worldLevels[selectedWorld]}")
        delay(300)
    }
}
