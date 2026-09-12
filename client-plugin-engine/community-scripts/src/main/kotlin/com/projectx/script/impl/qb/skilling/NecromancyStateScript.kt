package com.projectx.script.impl.qb.skilling

import org.projectx.core.game.skill.Skill
import com.projectx.game.chat.MessageType
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.XPDrop
import com.projectx.util.gaussian

private val PLATFORM = setOf(127315, 127316, 127314, 129034, 129033, 129032)
private val RITUAL_COMPONENTS = setOf(
    30501, 30502, 30503, 30504, 30505, 30506, 30507, 30508, 30509, 30510, 30511,
    30512, 30513, 30514, 30515, 30516, 30517, 30518, 30519, 30520, 30521, 30921,
    30922, 30923, 30924, 30925, 30926, 30927, 30928, 30929, 30930, 30931, 30932,
    30933, 30934, 30935, 30936, 30937, 30938, 30939, 30940, 30941
)
private const val WANDERING_SOUL = 30493
private const val SHAMBLING_HORROR = 30494
private const val SPARKLING_GLYPH_VISIBLEID = 30492
private const val DEFILE = 30500
private val SOUL_STORM_VISIBLEIDS = setOf(30498, 30499)
private val CORRUPT_GLYPHS = setOf(30495, 30496, 30497)
private val NECROMANCY_POT_REGEX = Regex(".*Extreme necromancy.*", RegexOption.IGNORE_CASE)

private val RITUAL_ACTIVE_VARBIT = 53856
private val ACTIVE_RITUAL_ID_VAR = 11182
private val ACTIVE_RITUAL_TIME_VAR = 11183
private val ACTIVE_RITUAL_ENDTIME_VAR = 11184

private var lastDisturbanceTime: Long = 0
private const val DISTURBANCE_COOLDOWN_MS = 6000

private var NECROMANCY_POT_TIMER = System.currentTimeMillis()
private val NECROMANCY_POT_MINUTES_UNTIL_REFIL = 10

@ScriptDescription(
    name = "NecromancyStateScript",
    version = "1.0",
    author = "QB",
    description = "Does all the necromancy rituals."
)
class NecromancyStateScript : StateMachineScript<NecromancyStateScript>(), ConfigurableScript {
    override fun getStartState() = CheckDisturbances

    val worldHop = BooleanConfigItem(
        name = "World Hop", description = "Enable world hop", initialValue = false
    )

    val handleDisturbances = BooleanConfigItem(
        name = "Handle Disturbances", description = "Handle disturbances", initialValue = false
    )

}

object CheckDisturbances : State<NecromancyStateScript>() {
    override suspend fun NecromancyStateScript.checkNext() = if (localPlayer.animationId != 35520) StartRitual else {
        if (handleDisturbances.value) checkDisturbanceState()
        null
    }

    override suspend fun NecromancyStateScript.stateLoop() {
        if (worldHop.value && checkWorldPop()) return

        delay(50, 150)
    }
}

private data class Disturbance(
    val predicate: (NPC) -> Boolean,
    val createState: () -> State<NecromancyStateScript>
)

private val disturbances = listOf(
    Disturbance({ it.id == WANDERING_SOUL }, ::HandleSoul),
    Disturbance({ it.id == SHAMBLING_HORROR }, ::HandleHorror),
    Disturbance({ it.typeId == SPARKLING_GLYPH_VISIBLEID }, ::HandleSparkling),
    Disturbance({ SOUL_STORM_VISIBLEIDS.contains(it.typeId) }, ::HandleStorm),
    Disturbance({ it.typeId == DEFILE }, ::HandleDefile),
    Disturbance({ CORRUPT_GLYPHS.contains(it.id) }, ::HandleCorrupt)
)

private fun checkDisturbanceState(): State<NecromancyStateScript>? {
    if (System.currentTimeMillis() - lastDisturbanceTime < DISTURBANCE_COOLDOWN_MS)
        return null
    return disturbances.firstNotNullOfOrNull { (predicate, createState) ->
        findClosestNPC(15, checkReachable = false, predicate)?.let { createState() }
    }
}

abstract class DisturbanceState() : State<NecromancyStateScript>() {
    protected var handled: Boolean = false
    private var timeout: Int = gaussian(25000, 5000)
    private val start = System.currentTimeMillis()

    fun finish() {
        lastDisturbanceTime = System.currentTimeMillis()
        handled = true
    }

    override suspend fun NecromancyStateScript.checkNext() =
        if ((System.currentTimeMillis() - start) > timeout || handled) StartRitual else null

    abstract override suspend fun NecromancyStateScript.stateLoop()
}

class HandleSoul : DisturbanceState() {
    override suspend fun NecromancyStateScript.stateLoop() {
        findClosestNPC(WANDERING_SOUL)?.let { soul ->
            if (soul.interact("Dismiss")) {
                waitForXPDrop(Skill.NECROMANCY)
                finish()
            }
        }
    }
}

class HandleSparkling : DisturbanceState() {
    override suspend fun NecromancyStateScript.stateLoop() {
        findClosestNPC { it.typeId == SPARKLING_GLYPH_VISIBLEID }?.let { restore ->
            if (restore.interact("Restore")) {
                waitForXPDrop(Skill.NECROMANCY)
                finish()
            }
        }
    }
}

class HandleHorror : DisturbanceState() {
    override suspend fun NecromancyStateScript.stateLoop() {
        findClosestNPC(SHAMBLING_HORROR)?.let { horror ->
            delay(300, 500)
            if (horror.interact("Sever link")) {
                waitForChatContaining(MessageType.UNFILTERABLE, "shambling horror is targeting")
                var targetComponent: NPC? = null
                delayUntil(6000) {
                    targetComponent =
                        findClosestNPC { RITUAL_COMPONENTS.contains(it.id) && it.spotAnims.any { spotAnim -> spotAnim.id == 7977 } }
                    targetComponent != null
                }
                delay(425, 1620)
                targetComponent?.interact(if (targetComponent!!.name.contains("depleted")) 2 else 0) == true
                waitForXPDrop(Skill.NECROMANCY)
                finish()
            }
        }
    }
}

class HandleCorrupt : DisturbanceState() {
    override suspend fun NecromancyStateScript.stateLoop() {
        delay(250, 350)
        for (id in CORRUPT_GLYPHS) {
            if (interactClosestNPC(id, "Deactivate")) {
                waitForChatContaining(MessageType.UNFILTERABLE, "You deactivate the corrupt glyph")
                delay(123, 325)
            }
        }
        finish()
    }
}

class HandleStorm : DisturbanceState() {
    override suspend fun NecromancyStateScript.stateLoop() {
        findClosestNPC { SOUL_STORM_VISIBLEIDS.contains(it.typeId) }?.let { storm ->
            if (storm.interact("Dissipate")) {
                waitForXPDrop(Skill.NECROMANCY)
                delayUntil(8000) { storm.spotAnims.any { it.id == 7916 } }
                delay(252, 325)
                findClosestReachableNPC { it.hasOption("Dissipate") }?.interact("Dissipate")
                delayUntil(8000) { storm.spotAnims.any { it.id == 7917 } }
                delay(252, 325)
                findClosestReachableNPC { it.hasOption("Dissipate") }?.interact("Dissipate")
                waitForXPDrop(Skill.NECROMANCY)
                finish()
            }
        }
    }
}

class HandleDefile : DisturbanceState() {
    override suspend fun NecromancyStateScript.stateLoop() {
        findClosestNPC(DEFILE)?.let { siphon ->
            if (siphon.interact("Siphon")) {
                waitThenDelayUntil(856) { localPlayer.isAnimating }
                while (siphon.exists() && !handled) {
                    if (siphon.spotAnims.any { it.id == 7930 }) {
                        delay(225, 350)
                        if (findClosestNPC { it.hasOption("Siphon") }?.interact("Siphon") == true)
                            delayUntil(30000) { !siphon.spotAnims.any { it.id == 7930 } || !siphon.exists() || handled }
                    }
                    delay(150, 100)
                }
                finish()
            }
        }
    }

    override fun NecromancyStateScript.onStateEvent(event: Event) {
        if (event is XPDrop && event.skill == Skill.NECROMANCY)
            finish()
    }
}

object StartRitual : State<NecromancyStateScript>() {
    override suspend fun NecromancyStateScript.checkNext() =
        if (localPlayer.animationId == 35520 && varps.getVarBit(RITUAL_ACTIVE_VARBIT) > 0) CheckDisturbances else null

    override suspend fun NecromancyStateScript.stateLoop() {

        val elapsedTime = System.currentTimeMillis() - NECROMANCY_POT_TIMER

        if (inventory.hasItem(NECROMANCY_POT_REGEX) && elapsedTime >= NECROMANCY_POT_MINUTES_UNTIL_REFIL * 60 * 1000) {
            inventory.clickItem(NECROMANCY_POT_REGEX, 1)
            delay(200, 111)
            NECROMANCY_POT_TIMER = System.currentTimeMillis()
        }

        if (varps.getVarBit(RITUAL_ACTIVE_VARBIT) == 0) {
            val pedestal = findClosestObject { obj -> obj.hasOption("Replace focus") } ?: return

            if (findClosestNPC { it.name.contains("depleted") } != null) {
                pedestal.interact("Repair all")
                delayUntil(30000) { findClosestNPC { it.name.contains("depleted") } == null }
                return
            }

            if (findClosestObject { PLATFORM.contains(it.id) }?.interact("Start ritual") == true) {
                delayUntil(30000) { localPlayer.isAnimating }
                delay(3500, 3000)
            }
            delay(50, 150)
            return
        }

        if (interactClosestObject("Continue ritual")) {
            delay(600, 200)
            delayUntil(2000) { localPlayer.isAnimating }
            delay(3500, 3000)
            delayUntil(2000) { localPlayer.isAnimating }
        }
        delay(50, 150)
    }
}