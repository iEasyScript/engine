package com.projectx.script.impl.trent.gemminer

import org.projectx.core.game.skill.Skill
import com.projectx.game.chat.MessageType
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.image
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar
import com.projectx.ui.backend.native.GraphicIds
import com.projectx.ui.backend.native.graphicTexture
import com.projectx.util.formatElapsedTime
import com.projectx.util.gaussian
import com.projectx.util.getFormattedXpPerHour
import com.projectx.util.getUnitsPerHour

@ScriptDescription(
    name = "Gem Miner",
    version = "1.0.0",
    author = "Trent",
    description = "Mines gems in Al Kharid"
)
class GemMiner : StateMachineScript<GemMiner>() {
    var startTime = 0L
    var startingXp = 0

    override fun onStart() {
        startTime = System.currentTimeMillis()
        startingXp = getXp(Skill.MINING)
    }

    override fun getStartState() = Mine

    override fun render() {
        ImGuiDsl.window("Gem Miner") {
            image(graphicTexture(GraphicIds.MINING), 32f, 32f)
            text("Runtime: ${formatElapsedTime(System.currentTimeMillis(), startTime)}")
            text("XP/hr: ${getFormattedXpPerHour(startingXp, getXp(Skill.MINING), startTime)}")
            xpProgressBar(Skill.MINING)
        }
    }
}

private var bagFull = false
private val GEM_BAG = Regex("Gem bag( \\(upgraded\\))?")

object Mine: State<GemMiner>() {
    override suspend fun GemMiner.checkNext(): State<GemMiner>? {
        if (!inventory.isFull) return null
        return if (!bagFull) Fill else Deposit
    }

    override suspend fun GemMiner.stateLoop() {
        if (localPlayer.isMoving) return

        if (!interactClosestReachableObject(113047, "Mine")) {
            if (inventory.clickItem("Mystical sand seed", "Plant"))
                waitThenDelayUntil(1200) { findClosestReachableObject(113047) != null }
            return
        }
        waitForXPDrop(Skill.MINING, timeoutMillis = gaussian(31592L, 5529L))
        delay(11582, 10592)
    }
}

object Fill: State<GemMiner>() {
    override suspend fun GemMiner.checkNext() = if (!inventory.isFull || bagFull) Mine else null

    override suspend fun GemMiner.stateLoop() {
        if (inventory.clickItem(GEM_BAG, "Fill"))
            delay(1328, 869)
    }

    override fun GemMiner.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("You can't store anymore of the following gem"))
            bagFull = true
        if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("Your gem bag is now full"))
            bagFull = true
    }
}

object Deposit: State<GemMiner>() {
    override suspend fun GemMiner.checkNext() = if (!inventory.isFull) Mine else null

    override suspend fun GemMiner.stateLoop() {
        val bankChest = findClosestReachableObject("Bank chest")
        if (bankChest == null) {
            interactComponent(1, 1887, 1, 205)
            waitThenDelayUntil(1200) { findClosestReachableObject("Bank chest") != null }
            return
        }
        if (inventory.find { it.id != -1 && GEM_BAG.matches(it.name) }?.useOn(bankChest) == true) {
            waitForChatContaining(MessageType.FILTERABLE, "Emptied")
            bagFull = false
            bankChest.interact("Load Last Preset from")
            delayUntil { !inventory.isFull }
        }
    }
}