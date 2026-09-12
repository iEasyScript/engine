package com.projectx.script.impl.trent.portermine

import org.projectx.core.game.skill.Skill
import com.projectx.game.chat.MessageType
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.findClosestObjectToTile
import com.projectx.script.api.findClosestReachableObject
import com.projectx.script.api.inventory
import com.projectx.script.api.spotAnims
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.script.impl.trent.EquipPorters

private val rockertunitySpotAnims = intArrayOf(7164, 7165)

@ScriptDescription(
    name = "Porter Mining",
    version = "1.0.0",
    author = "Trent",
    description = "Mines chosen rocks with porters"
)
class PorterMining : StateMachineScript<PorterMining>() {
    lateinit var rockName: String
    fun hasRockName() = ::rockName.isInitialized

    override fun getStartState() = Init

    override fun onStart() {
        addParallelScript(EquipPorters())
    }
}

object Init: State<PorterMining>() {
    override suspend fun PorterMining.checkNext() = if (hasRockName()) Mine() else null

    override suspend fun PorterMining.stateLoop() { }

    override fun PorterMining.onStateEvent(event: Event) {
        if (event !is ManualDoAction || event.target !is SceneObject) return
        val rock = event.target as SceneObject
        if (!hasRockName() && rock.hasOption("Mine"))
            rockName = rock.name()
    }
}

private val oreboxes = """.*ore box$""".toRegex()

class Mine: State<PorterMining>() {
    var currentRock: SceneObject? = null
    var oreboxFull = false

    override suspend fun PorterMining.checkNext() = if (inventory.freeSlots <= 1 && oreboxFull) { stop(); null } else null

    override suspend fun PorterMining.stateLoop() {
        if (inventory.freeSlots <= 1 && inventory.hasItem(oreboxes)) {
            inventory.clickItem(oreboxes, "Fill")
            delayUntil(2500) { inventory.freeSlots > 1 }
            return
        }
        currentRock = findClosestReachableObject(24) { it.name() == rockName && it.hasOption("Mine") }
        val rockertunity = spotAnims.find { rockertunitySpotAnims.contains(it.id) }
        if (rockertunity != null)
            currentRock = findClosestObjectToTile(rockertunity.tile) { it.name() == rockName && it.hasOption("Mine") }
        if (currentRock?.interact("Mine") == true) {
            waitForXPDrop(Skill.MINING)
            delay(5529, 10592)
        }
    }

    override fun PorterMining.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("You are not able to deposit anything in your backpack into your ore box."))
            oreboxFull = true
    }
}