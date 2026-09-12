package com.projectx.script.impl.devin

import world.gregs.voidps.type.Tile
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.util.gaussian

private const val KEEP_TARGET_RADIUS = 5
private const val SWITCH_CLOSER_BY = 3
private val RETARGET_COOLDOWN_MS = 8_000L..14_000L

@ScriptDescription(
    name = "Generic Pickpocket",
    version = "1.0.0",
    author = "Devin",
    description = "Pickpockets anything and stands near the bank to heal."
)
class GenericPickpocket : StateMachineScript<GenericPickpocket>() {
    lateinit var npcName: String
    var startTile: Tile = Tile.EMPTY
    private var targetIndex = -1
    private var nextRetargetAt = 0L

    fun selectedNPCName() = ::npcName.isInitialized

    override fun getStartState() = Init

    fun clearTarget() {
        targetIndex = -1
    }

    fun selectTarget(): NPC? {
        val current = npcs[targetIndex]?.takeIf { pickpocketable(it) }
        val closest = findClosestNPC { pickpocketable(it) } ?: return current
        if (current == null) return lockTarget(closest)
        if (closest.serverIndex == current.serverIndex) return current

        val currentDistance = localPlayer.tile.getDistance(current.tile)
        val closerBy = currentDistance - localPlayer.tile.getDistance(closest.tile)
        val shouldSwitch = currentDistance > KEEP_TARGET_RADIUS &&
            closerBy >= SWITCH_CLOSER_BY &&
            System.currentTimeMillis() >= nextRetargetAt
        return if (shouldSwitch) lockTarget(closest) else current
    }

    private fun pickpocketable(npc: NPC) =
        npc.currentHealth > 0 && npc.name() == npcName && npc.tile.withinDistance(localPlayer.tile, 20)

    private fun lockTarget(npc: NPC): NPC {
        targetIndex = npc.serverIndex
        nextRetargetAt = System.currentTimeMillis() + RETARGET_COOLDOWN_MS.random()
        return npc
    }
}

object Init : State<GenericPickpocket>() {
    override suspend fun GenericPickpocket.checkNext(): State<GenericPickpocket>? {
        return if (selectedNPCName()) Pickpocket else null
    }

    override suspend fun GenericPickpocket.stateLoop() {}

    override fun GenericPickpocket.onStateEvent(event: Event) {
        if (event !is ManualDoAction || event.target !is NPC) return
        val npc = event.target as NPC
        if (!selectedNPCName() && npc.hasOption("Pickpocket")) {
            npcName = npc.name()
            startTile = npc.tile
        }
    }
}

object Pickpocket : State<GenericPickpocket>() {
    override suspend fun GenericPickpocket.checkNext() = if (healthCurrent < 500 || inventory.isFull) Heal else null

    override suspend fun GenericPickpocket.stateLoop() {
        if (inCombat) {
            val target = findNPC { it.interactingWith(localPlayer) && it.currentHealth > 0 }
            if (target != null) {
                if (!localPlayer.isInteracting && target.interact("Attack"))
                    delayUntil(gaussian(2115L, 4421L)) { localPlayer.isInteracting }
                return
            }
        }

        if (inventory.freeSlots < 2) {
            inventory.forEach {
                if (!it.getDef().isStackable()) {
                    it.click("Drop")
                    delay(554, 733)
                }
            }
        }

        val target = selectTarget()
        when {
            target == null -> if (walkTo(startTile.randomize(3), true))
                waitThenDelayUntil(gaussian(6436L, 2114L)) { localPlayer.tile.withinDistance(startTile, 3) }
            target.interact("Pickpocket") -> delay(712, 969)
            else -> clearTarget()
        }
    }
}

object Heal : State<GenericPickpocket>() {
    override suspend fun GenericPickpocket.checkNext() =
        if (healthCurrent > 500 && !inventory.isFull) Pickpocket else null

    override suspend fun GenericPickpocket.stateLoop() {
        val bank = findClosestReachableObjectWithOption("Load Last Preset from", 25) ?: return
        bank.interact("Load Last Preset from")
        waitThenDelayUntil(1200, gaussian(14285L, 6629L)) { !inventory.isFull && healthPercent > 90.0 }
    }
}
