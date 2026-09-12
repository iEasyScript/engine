package com.projectx.script.impl.trent.scriptureofelidinis

import com.projectx.game.chat.MessageType
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.gaussian

@ScriptDescription(
    name = "Scripture of Elidinis",
    version = "1.0.0",
    author = "Trent",
    description = "To be run in tandem with other scripts, will pause other scripts to solve the scripture"
)
class ScriptureOfElidinis : StateMachineScript<ScriptureOfElidinis>() {
    override fun getStartState() = CheckForSpirit()
}

private const val guideSpirit = 17720
private const val stabilizeSpirit = 17739
private const val avoidSpirit = 17802
private const val chaseSpirit = 18222
private val soulRealIds = intArrayOf(guideSpirit, stabilizeSpirit, avoidSpirit, chaseSpirit)

class CheckForSpirit : State<ScriptureOfElidinis>() {
    var spirit: NPC? = null

    override suspend fun ScriptureOfElidinis.checkNext() = if (spirit?.exists() == true) CatchSpirit() else null

    override suspend fun ScriptureOfElidinis.stateLoop() {
        spirit = findClosestNPC { soulRealIds.contains(it.id) }
        delay(500, 250)
    }
}

class CatchSpirit : State<ScriptureOfElidinis>() {
    var caught = false
    val startTime = System.currentTimeMillis()

    override suspend fun ScriptureOfElidinis.checkNext() = if (caught) CheckForSpirit() else null

    override suspend fun ScriptureOfElidinis.stateLoop() {
        if (caught) return
        pauseOthers()
        if (System.currentTimeMillis() - startTime > 30000) {
            caught = true
            moveOn()
            return
        }
        val spirit = findClosestNPC { soulRealIds.contains(it.id) } ?: return
        when (spirit.id) {
            guideSpirit -> if (spirit.interact("Guide")) {
                delayUntil(gaussian(14592L, 3010L)) { caught }
                moveOn()
            }
            stabilizeSpirit -> if (spirit.interact("Stabalise"))
                delayUntil(gaussian(14592L, 3010L)) { spirit.id == guideSpirit }
            avoidSpirit -> {
                val safeTile = calculateClosestReachableSafeTile(localPlayer.tile, listOf(DangerZone(spirit.tile, 8))) ?: return
                if (walkTo(safeTile.randomize(1), false))
                    delay(1138, 985)
            }
            chaseSpirit -> {
                if (walkTo(spirit.tile.randomize(1), false))
                    delay(1138, 985)
            }
        }
    }

    override fun ScriptureOfElidinis.onStateEvent(event: Event) {
        if (event !is Chat || event.messageType != MessageType.UNFILTERABLE && event.messageType != MessageType.FILTERABLE) return
        if (event.message.contains("As you guide the"))
            caught = true
    }

    suspend fun ScriptureOfElidinis.moveOn() {
        resumeOthers()
        delayUntil(gaussian(14592L, 3010L)) { findClosestNPC { soulRealIds.contains(it.id) } == null }
    }
}