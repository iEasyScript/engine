package com.projectx.script.impl.qb.Temporary

import world.gregs.voidps.type.Tile
import com.projectx.game.interfaces.IFSlot
import org.projectx.core.game.combat.Effect
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.random

@ScriptDescription(
    name = "Beach Event",
    version = "1.0.0",
    author = "Query & Billy",
    description = "Fully automated beach event using state machine pattern for various activities.",
)
class BeachEvent : StateMachineScript<BeachEvent>(), ConfigurableScript {
    val selectedActivity = EnumConfigItem(
        name = "Beach Activity",
        description = "Select which beach activity to do",
        enumValues = BeachActivity.entries.toTypedArray(),
        initialValue = BeachActivity.SANDCASTLE
    )

    val enableDebug = BooleanConfigItem(
        name = "Debug Mode", description = "Enable debug output", initialValue = true
    )

    val useDrinks = BooleanConfigItem(
        name = "Use Drinks", description = "Drink the respective drink for what is being trained.", initialValue = true
    )

    val useSpotlight = BooleanConfigItem(
        name = "Use Spotlight", description = "Automatically switch to spotlight activity", initialValue = true
    )

    val waitForHappyHour = BooleanConfigItem(
        name = "Wait for Happy Hour", description = "Wait for Happy Hour before starting", initialValue = false
    )

    var currentActivity: BeachActivity = BeachActivity.SANDCASTLE

    val activity: BeachActivity
        get() = currentActivity

    val debug: Boolean
        get() = enableDebug.value

    val spotlight: Boolean
        get() = useSpotlight.value

    val happyHour: Boolean
        get() = waitForHappyHour.value

    val BEACH_TEMP_VARBIT = 28441
    val HAPPY_HOUR_VARBIT = 33485
    val SPOTLIGHT_ACTIVITY_VARBIT = 28460
    val MAX_BEACH_TEMP = 1500

    var activityCompletions = 0
    var animationDelay = 0

    override fun getStartState() = BeachMainState()

    enum class BeachActivity(
        val displayName: String,
        val location: Tile,
        val area: Area,
        val spotlightId: Int
    ) {
        COCONUT_SHY("Coconut Shy", Tile.of(3262, 3234, 0), Area.Circular(Tile.of(3262, 3234, 0), 5.0), 0),
        SANDCASTLE("Sandcastle Building", Tile.of(3256, 3240, 0), Area.Circular(Tile.of(3256, 3240, 0), 5.0), 1),
        BARBEQUES("Barbeques", Tile.of(3272, 3235, 0), Area.Circular(Tile.of(3272, 3235, 0), 5.0), 2),
        ROCK_POOLS("Rock Pools", Tile.of(3263, 3245, 0), Area.Circular(Tile.of(3263, 3245, 0), 5.0), 3),
        PALM_TREE("Palm Tree Farming", Tile.of(3256, 3229, 0), Area.Circular(Tile.of(3256, 3229, 0), 5.0), 4),
        BODYBUILDING("Body Building", Tile.of(3270, 3227, 0), Area.Circular(Tile.of(3270, 3227, 0), 5.0), 5),
        HOOK_A_DUCK("Hook a Duck", Tile.of(3275, 3243, 0), Area.Circular(Tile.of(3275, 3243, 0), 5.0), 6),
        DUNGEONEERING("Dungeoneering Hole", Tile.of(3249, 3236, 0), Area.Circular(Tile.of(3249, 3236, 0), 5.0), 7);

        companion object {
            fun fromSpotlightId(id: Int): BeachActivity {
                return entries.find { it.spotlightId == id } ?: SANDCASTLE
            }
        }
    }

    override fun onEvent(event: Event) {
        super.onEvent(event)
        if (event is Chat && debug) {
            println(event.message)
        }
    }

    fun getBeachTemperature(): Int {
        return varps.getVarBit(BEACH_TEMP_VARBIT)
    }

    fun isHappyHour(): Boolean {
        return varps.getVarBit(HAPPY_HOUR_VARBIT) == 1
    }

    fun getSpotlightActivity(): BeachActivity {
        val spotlightId = varps.getVarBit(SPOTLIGHT_ACTIVITY_VARBIT)
        return BeachActivity.fromSpotlightId(spotlightId)
    }
}

class BeachMainState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        if (interfaces.isOpen(1188)) {
            { continueDialogueContaining("Continue") }
        }

        if (happyHour && !isHappyHour()) {
            return BeachIdleState()
        }

        currentActivity = if (spotlight) getSpotlightActivity() else selectedActivity.value

        if (debug && spotlight) {
            println("Using spotlight activity: ${currentActivity.displayName}")
        }

        return when (currentActivity) {
            BeachEvent.BeachActivity.COCONUT_SHY -> CoconutShyState()
            BeachEvent.BeachActivity.SANDCASTLE -> SandcastleState()
            BeachEvent.BeachActivity.BARBEQUES -> BarbequeState()
            BeachEvent.BeachActivity.ROCK_POOLS -> RockPoolState()
            BeachEvent.BeachActivity.PALM_TREE -> PalmTreeState()
            BeachEvent.BeachActivity.BODYBUILDING -> BodybuildingState()
            BeachEvent.BeachActivity.HOOK_A_DUCK -> HookADuckState()
            BeachEvent.BeachActivity.DUNGEONEERING -> DungeoneeringState()
        }
    }

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Main controller")
            println("Current beach temperature: ${getBeachTemperature()}/${MAX_BEACH_TEMP}")
            println("Happy Hour active: ${isHappyHour()}")
            println("Current spotlight: ${getSpotlightActivity().displayName}")
        }

        delay(random(500, 1000))
    }
}

class BeachIdleState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        if (getBeachTemperature() < MAX_BEACH_TEMP) {
            if (!happyHour || isHappyHour()) {
                return BeachMainState()
            }
        }

        return null
    }

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Idle")
            if (getBeachTemperature() >= MAX_BEACH_TEMP) {
                println("Beach temperature maxed out (${getBeachTemperature()}/${MAX_BEACH_TEMP})")
            }
            if (happyHour && !isHappyHour()) {
                println("Waiting for Happy Hour")
            }
        }

        delay(random(5000, 10000))
    }
}

class CoconutShyState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        return if (random(0, 50) == 0) BeachMainState() else null
    }

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Coconut Shy activity")
        }

        if (localPlayer.isAnimating) {
            animationDelay = 0
            delay(random(1000, 2000))
            return
        }

        val coconutShy = findClosestObject (60) { it.name().contains("Coconut shy", ignoreCase = true) }
        if (coconutShy == null) {
            if (debug) println("No coconut shy found")
            delay(random(1000, 2000))
            return
        }

        if(localPlayer.headbars.firstOrNull { it.type == 13 } == null)
        {
            coconutShy.interact("Play")
            activityCompletions++
            delay(random(2000, 3000))
            return
        }
    }
}

class SandcastleState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        return if (random(0, 50) == 0) BeachMainState() else null
    }

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Sandcastle building activity")
        }

       println("Current animation: ${localPlayer.animation}")

        if (localPlayer.isAnimating) {
            animationDelay = 0
            delay(random(1000, 2000))
            return
        }

        val sandcastleSpot = findClosestObject (60){ it.name().contains("Sand Pyramid", ignoreCase = true) }
        if (sandcastleSpot == null) {
            if (debug) println("No sandcastle spot found")
            delay(random(1000, 2000))
            return
        }

        if(localPlayer.headbars.firstOrNull { it.type == 13 } == null) {
            sandcastleSpot.interact("Build")
            activityCompletions++
            delay(random(2000, 3000))
        }
    }
}

class BarbequeState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        return if (random(0, 50) == 0) BeachMainState() else null
    }

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Barbeque activity")
        }

        if (localPlayer.isAnimating) {
            animationDelay = 0
            delay(random(1000, 2000))
            return
        }

        val barbeque = findClosestObject(60) { it.name().contains("Grill", ignoreCase = true) }
        if (barbeque == null) {
            if (debug) println("No Grill found")
            delay(random(1000, 2000))
            return
        }

        if(localPlayer.headbars.firstOrNull { it.type == 13 } == null)
        {
            if (debug) println("Interacting with Grill")
            barbeque.interact("Use")
            activityCompletions++
            delay(random(2000, 3000))
        } else {
            if (debug) println("Already interacting with Grill")
        }
    }
}

class RockPoolState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        return if (random(0, 50) == 0) BeachMainState() else null
    }
    var timesinceidle =0

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Rock pools activity")
        }

        if (inventory.isFull) {
            if (debug) println("Inventory full with fish, finding Wellington to hand in")

            val wellington = findClosestNPC(60) { it.name == "Wellington" }
            if (wellington != null) {
                wellington.interact("Hand in fish")
                delayUntil(5000) { !inventory.isFull }
                delay(random(1000, 2000))
                return
            } else {
                if (debug) println("No Wellington NPC found for fish hand-in")
                delay(random(1000, 2000))
                return
            }
        }

        if (localPlayer.isAnimating) {
            timesinceidle = 0
            animationDelay = 0
            delay(random(1000, 2000))
            return
        }

        if(!localPlayer.isAnimating)
        {
            if(timesinceidle == 0)
            {
                timesinceidle = System.currentTimeMillis().toInt()
                return
            }

            if(System.currentTimeMillis().toInt() - timesinceidle < 5000)
            {
                return
            }
        }

        val rockPool = findClosestNPC (60){ it.name().contains("Fishing spot", ignoreCase = true) }
        if (rockPool == null) {
            if (debug) println("No fishing spot found")
            delay(random(1000, 2000))
            return
        }

        rockPool.interact("Catch")
        activityCompletions++
        delay(random(2000, 3000))
    }
}

class PalmTreeState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        return if (random(0, 50) == 0) BeachMainState() else null
    }

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Palm tree activity")
        }

        if (localPlayer.isAnimating) {
            animationDelay = 0
            delay(random(1000, 2000))
            return
        }

        if (inventory.isFull) {
            if (debug) println("Inventory full, depositing coconuts")

            val coconutPile = findClosestObject(60) { it.name().contains("Pile of coconuts", ignoreCase = true) }
            if (coconutPile != null) {
                coconutPile.interact("Deposit coconuts")
                delayUntil(5000) { !inventory.isFull }
                delay(random(1000, 2000))
                return
            } else {
                if (debug) println("No coconut pile found for depositing")
                delay(random(1000, 2000))
                return
            }
        }

        val palmTree = findClosestObject { it.name().contains("Palm tree", ignoreCase = true) }
        if (palmTree == null) {
            if (debug) println("No palm tree found")
            delay(random(1000, 2000))
            return
        }

        palmTree.interact("Pick coconut")
        activityCompletions++
        delay(random(2000, 3000))
    }
}

class BodybuildingState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        return if (random(0, 50) == 0) BeachMainState() else null
    }

    var timesinceidle = 0

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Bodybuilding activity")
        }

        val npcanimation = findClosestNPC(20){it.name == "Greta"}?.animationId

        if(localPlayer.animationId != npcanimation && npcanimation != -1)
        {
            when(npcanimation)
            {
                26551 -> println("Idle")
                26549 -> {IFSlot(796, 36, -1).click(1)
                    println("Raise") }
                26554 ->  {IFSlot(796, 26, -1).click(1)
                    println("Fly")}
                26553 -> {IFSlot(796, 16, -1).click(1)
                    println("Lundge")}
                26552 -> {IFSlot(796, 6, -1).click(1)
                    println("Curl" ) }
                else -> println("Unknown animation: $npcanimation")
            }
            delay(random(1000, 2000))
        }

        if (localPlayer.isAnimating) {
            timesinceidle = 0
            animationDelay = 0
            delay(random(1000, 2000))
            return
        }

        if(!localPlayer.isAnimating)
        {
            if(timesinceidle == 0)
            {
                timesinceidle = System.currentTimeMillis().toInt()
                return
            }

            if(System.currentTimeMillis().toInt() - timesinceidle < 5000)
            {
                return
            }
        }

        val muscleBeach = findClosestObject(60) { it.name().contains("Body building podium", ignoreCase = true) }
        if (muscleBeach == null) {
            if (debug) println("No muscle beach found")
            delay(random(1000, 2000))
            return
        }

        if( localPlayer.headbars.firstOrNull { it.type == 13 } != null) {
            if (debug) println("Already interacting with Muscle Beach")
            return
        }

        muscleBeach.interact("Workout")
        activityCompletions++
        delay(random(2000, 3000))
    }
}

class HookADuckState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        return if (random(0, 50) == 0) BeachMainState() else null
    }

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Hook-a-Duck activity")
        }

        if (localPlayer.isAnimating) {
            animationDelay = 0
            delay(random(1000, 2000))
            return
        }

        val duckPond = findClosestObject (60){ it.name().contains("Hook-a-Duck", ignoreCase = true) }
        if (duckPond == null) {
            if (debug) println("No duck pond found")
            delay(random(1000, 2000))
            return
        }

        if( localPlayer.headbars.firstOrNull { it.type == 13 } == null) {
            if (debug) println("Interacting with Hook-a-Duck pond")
            duckPond.interact("Play")
            activityCompletions++
            delay(random(2000, 3000))
        } else {
            if (debug) println("Already interacting with Hook-a-Duck pond")
        }
    }
}

class DungeoneeringState : State<BeachEvent>() {
    override suspend fun BeachEvent.checkNext(): State<BeachEvent>? {
        return if (random(0, 50) == 0) BeachMainState() else null
    }

    override suspend fun BeachEvent.stateLoop() {
        if (debug) {
            println("Beach state: Dungeoneering activity")
        }

        if (localPlayer.isAnimating) {
            animationDelay = 0
            delay(random(1000, 2000))
            return
        }

        if (useDrinks.value) {
            if (Effect.LEMON_SOUR_BEACH_COCKTAIL.notActive && inventory.clickItem("Lemon sour (beach cocktail)", 1)) {
                delayUntil(2500) { Effect.LEMON_SOUR_BEACH_COCKTAIL.active }
                return
            }
            if (Effect.BEACH_HOLE_COCKTAIL.notActive && inventory.clickItem("A Hole in One (beach cocktail)", 1)) {
                delayUntil(2500) { Effect.BEACH_HOLE_COCKTAIL.active }
                return
            }
        }

        val dungeoneeringHole = findClosestObject(60) { it.name().contains("Dungeoneering hole", ignoreCase = true) }
        if (dungeoneeringHole == null) {
            if (debug) println("No dungeoneering hole found")
            delay(random(1000, 2000))
            return
        }

        if( localPlayer.headbars.firstOrNull { it.type == 13 } == null) {
            if (debug) println("Interacting with Dungeoneering hole")
            dungeoneeringHole.interact("Dungeoneer")
            activityCompletions++
            delay(random(2000, 3000))
        } else {
            if (debug) println("Already interacting with Dungeoneering hole")
        }
    }
}