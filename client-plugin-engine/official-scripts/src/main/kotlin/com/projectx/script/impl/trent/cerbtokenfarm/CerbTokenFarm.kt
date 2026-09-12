package com.projectx.script.impl.trent.cerbtokenfarm
import com.projectx.game.tileOfSceneLocal

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import com.projectx.game.chat.MessageType
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.impl.trent.combatutils.optimizedNecromancyRevo
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.image
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar
import com.projectx.ui.backend.native.GraphicIds
import com.projectx.ui.backend.native.graphicTexture
import com.projectx.util.*

@ScriptDescription(
    name = "Cerberus Token Farmer",
    version = "1.0.0",
    author = "Trent",
    description = "Kills Cerberus in OSRS from RS3"
)
class CerbTokenFarm : StateMachineScript<CerbTokenFarm>() {
    var startTime = 0L
    var startingXp = 0
    var totalTokensGained = 0
    
    override fun onStart() { 
        addParallelScript(CerbCombatRotation())
        startTime = System.currentTimeMillis()
        startingXp = getXp(Skill.DUNGEONEERING)
        totalTokensGained = 0
    }
    
    override fun getStartState() = Enter
    
    override fun onEvent(event: Event) {
        if (event is Chat)
            println(event.messageType.toString() + ", " + event.message)
        if (event !is Chat || event.messageType != MessageType.UNFILTERABLE) return
        if (event.message.contains("You find") && event.message.contains("dungeoneering tokens")) {
            val tokenAmount = Regex("You find ([\\d,]+) dungeoneering tokens").find(event.message)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            if (tokenAmount != null)
                totalTokensGained += tokenAmount
        }
    }
    
    private val tokensPerHour get() = getUnitsPerHour(totalTokensGained, startTime)
    
    private fun getCurrentStage(): String {
        return when (currentState) {
            is Enter -> "Entering Undercity"
            is RunToCerb -> "Moving to Cerberus"
            is Kill -> "Fighting Cerberus"
            is Exit -> "Exiting Instance"
            else -> "Unknown"
        }
    }
    
    override fun render() {
        ImGuiDsl.window("Cerberus Token Farm") {
            image(graphicTexture(GraphicIds.DUNGEONEERING), 32f, 32f)
            text("Runtime: ${formatElapsedTime(System.currentTimeMillis(), startTime)}")
            text("Tokens/hr: ${format(tokensPerHour)}")
            text("XP/hr (inc. tokens): ${format(tokensPerHour + getXpPerHour(startingXp, getXp(Skill.DUNGEONEERING), startTime))}")
            xpProgressBar(Skill.DUNGEONEERING)
        }
    }
}

object Enter : State<CerbTokenFarm>() {
    override suspend fun CerbTokenFarm.checkNext() = if (!Tile.of(1761, 1343, 0).withinDistance(localPlayer.tile)) RunToCerb() else null

    override suspend fun CerbTokenFarm.stateLoop() {
        if (continueDialogueContaining("Normal mode")) {
            delayUntil { !Tile.of(1761, 1343, 0).withinDistance(localPlayer.tile) }
            return
        }
        if (interactClosestReachableObject("The Zamorakian Undercity", "Enter"))
            delayUntil(20000) { dialogueOptions.contains("Normal mode") }
    }
}

class RunToCerb: State<CerbTokenFarm>() {
    var cerberus: NPC? = null

    override suspend fun CerbTokenFarm.checkNext(): State<CerbTokenFarm>? {
        val cerbTile = tileOfSceneLocal(29, 71, 0) ?: return null
        return if (localPlayer.tile == cerbTile && cerberus != null) Kill(cerberus!!) else null
    }

    override suspend fun CerbTokenFarm.stateLoop() {
        val prepTile = tileOfSceneLocal(0, 50, 0) ?: return
        val surgeTile = tileOfSceneLocal(1, 51, 0) ?: return
        val runTile = tileOfSceneLocal(29, 59, 0) ?: return
        val cerbTile = tileOfSceneLocal(29, 71, 0) ?: return

        if (localPlayer.tile != cerbTile) {
            moveLocalAndVerify(prepTile)
            moveLocalAndVerify(surgeTile)
            if (surge()) {
                repeat(random(3, 6)) {
                    walkTo(runTile.randomize(2), true)
                    delay(415, 300)
                }
                delayUntil { !localPlayer.isMoving }
            }
            moveLocalAndVerify(cerbTile)
            while(cerberus == null) {
                cerberus = findClosestNPC("Cerberus Juvenile")
                delay(600, 1000)
            }
            return
        }
    }
}

private suspend fun CerbTokenFarm.moveLocalAndVerify(tile: Tile, minimap: Boolean = false): Boolean {
    while(tile != localPlayer.tile) {
        if (walkTo(tile, minimap)) {
            waitThenDelayUntil(1100) { !localPlayer.isMoving }
            delayUntil(1000) { tile == localPlayer.tile }
        }
        delay(100, 200)
    }
    return true
}

class Kill(val cerberus: NPC): State<CerbTokenFarm>() {
    override suspend fun CerbTokenFarm.checkNext() = if (!cerberus.exists() || cerberus.currentHealth <= 0) Exit else null

    override suspend fun CerbTokenFarm.stateLoop() {
        val witch = findClosestNPC("Chaos witch mender")
        if (witch?.tile?.withinDistance(localPlayer.tile, 6) == true && witch.currentHealth > 0) {
            if (!localPlayer.interactingWith(witch) && witch.interact("Attack"))
                delayUntil(3000) { witch.currentHealth <= 0 || !witch.exists() }
            return
        }
        val cerbTile = tileOfSceneLocal(29, 71, 0) ?: return
        if (localPlayer.tile != cerbTile) {
            moveLocalAndVerify(cerbTile)
            return
        }
        if (!localPlayer.interactingWith(cerberus) && cerberus.tile.withinDistance(localPlayer.tile, 5) == true) {
            cerberus.interact("Attack")
            delay(1200, 1000)
        }
    }
}

object Exit: State<CerbTokenFarm>() {
    override suspend fun CerbTokenFarm.checkNext() = if (Tile.of(1761, 1343, 0).withinDistance(localPlayer.tile)) Enter else null

    override suspend fun CerbTokenFarm.stateLoop() {
        val runTile = tileOfSceneLocal(0, 32, 0) ?: return
        while(!Tile.of(1761, 1343, 0).withinDistance(localPlayer.tile) && findClosestObject { it.id == 124297 }?.tile?.withinDistance(localPlayer.tile, 20) != true) {
            if (walkTo(runTile.randomize(1), true))
                delay(4300, 3000)
            delay(100, 100)
        }
        if (interactClosestObject(124297, "Escape"))
            delayUntil { dialogueOptions.contains("Yes, I want to leave the Zamorakian Undercity") }
        if (continueDialogueContaining("I want to leave")) {
            delayUntil { Tile.of(1761, 1343, 0).withinDistance(localPlayer.tile) }
            delay(3000, 3000)
        }
    }
}

class CerbCombatRotation : Script() {
    override suspend fun loop() {
        val cerb = findClosestNPC("Cerberus Juvenile") ?: return
        if (!inInstancedArea || healthCurrent <= 0 || (!localPlayer.interactingWith(cerb) && !localPlayer.interactingWith(findClosestNPC("Chaos witch mender") ?: return))) return
        optimizedNecromancyRevo()
    }
}