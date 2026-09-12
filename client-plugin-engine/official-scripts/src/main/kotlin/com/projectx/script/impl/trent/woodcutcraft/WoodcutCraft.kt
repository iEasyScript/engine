package com.projectx.script.impl.trent.woodcutcraft

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.util.gaussian

@ScriptDescription(
    name = "WoodcutCraft",
    version = "1.0.0",
    author = "Trent",
    description = "Woodcuts and fletches/crafts the logs it cuts"
)
class WoodcutCraft : StateMachineScript<WoodcutCraft>() {
    lateinit var treeName: String
    fun selectedTreeName() = ::treeName.isInitialized

    override fun getStartState() = Init
}

object Init: State<WoodcutCraft>() {
    override suspend fun WoodcutCraft.checkNext(): State<WoodcutCraft>? {
        return if (selectedTreeName()) Woodcut else null
    }

    override suspend fun WoodcutCraft.stateLoop() { }

    override fun WoodcutCraft.onStateEvent(event: Event) {
        if (event !is ManualDoAction || event.target !is SceneObject) return
        val obj = event.target as SceneObject
        if (!selectedTreeName() && obj.chopOption() != null)
            treeName = obj.name()
    }
}

private val logs = Regex(".*\\slogs$")
private val unf = Regex(".*(\\s\\(unstrung\\)| stock)$")

private val CHOP_OPTIONS = listOf("Chop down", "Cut down")
private fun SceneObject.chopOption() = CHOP_OPTIONS.firstOrNull { hasOption(it) }

object Woodcut: State<WoodcutCraft>() {
    override suspend fun WoodcutCraft.checkNext() = if (inventory.isFull) if (inventory.hasItem(unf)) Disassemble else Craft else null

    override suspend fun WoodcutCraft.stateLoop() {
        if (localPlayer.isAniMoving) return
        val tree = findClosestReachableObject(20) { it.name() == treeName && it.chopOption() != null } ?: return
        val option = tree.chopOption() ?: return
        if (!tree.interact(option)) return
        delayUntil(15592) { localPlayer.isAnimating || !tree.exists }
        // Wait on the tracked tree being depleted, not on raw animation state: the chop
        // animation briefly stops the instant a tree falls (and can flicker mid-chop), and
        // re-targeting then re-selects the same still-present tree. The idle-anim fallback
        // covers the short window where the felled loc lingers before the stump swap.
        delayUntil(gaussian(120000L, 15000L)) {
            !tree.exists || inventory.isFull || timeSinceLastAnim > gaussian(4200, 900)
        }
    }
}

object Craft: State<WoodcutCraft>() {
    override suspend fun WoodcutCraft.checkNext() = if (inventory.count(logs) < 2) Woodcut else null

    override suspend fun WoodcutCraft.stateLoop() {
        if (!makeXOpen) {
            if (inventory.clickItem(logs, "Craft"))
                delayUntil { makeXOpen }
            return
        }
        continueMakeX()
        delayUntil(timeoutMillis = 50000) { inventory.count(logs) < 2 }
    }
}

object Disassemble: State<WoodcutCraft>() {
    override suspend fun WoodcutCraft.checkNext() = if (!inventory.hasItem(unf)) Woodcut else null

    override suspend fun WoodcutCraft.stateLoop() {
        if (inventory.getItem(unf)?.disassemble() == true)
            delayUntil(timeoutMillis = 50000) { !inventory.hasItem(unf) }
    }
}