package com.projectx.script.impl.trent.archglacor
import com.projectx.game.tileOfLocal

import world.gregs.voidps.type.Tile
import org.projectx.core.game.combat.Ability
import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.impl.trent.AutoPrayerSwitcher
import com.projectx.script.impl.trent.CombatRotation
import com.projectx.util.random
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.interfaces.startOrRejoinInstance
import world.gregs.voidps.gameval.Gameval

private const val ENTRANCE_PORTAL = 121338
private const val EXIT_PORTAL = 121339

private val BSLAY_GEM = Gameval.requireId(Gameval.OBJ, "bslay_gem")
private val BSLAY_TARGET = Gameval.requireId(Gameval.VARBIT_PLAYER, "bslay_target")
private val BSLAY_KILLS_LEFT = Gameval.requireId(Gameval.VARBIT_PLAYER, "bslay_kills_left")
private val CHOOSE_TASK = Gameval.requireComponentHash("bslay_choose_task:click_layer")
    .let { IFSlot(it shr 16, it and 0xFFFF) }

private const val ARCH_GLACOR_TARGET = 43
private const val ARCH_GLACOR_TASK_OPTION = 42

private val hasArchGlacorTask
    get() = varps.getVarBit(BSLAY_TARGET) == ARCH_GLACOR_TARGET && varps.getVarBit(BSLAY_KILLS_LEFT) > 0

private val chooseTaskOpen get() = interfaces.isOpen(CHOOSE_TASK.interfaceId)

private suspend fun ArchGlacor.assignArchGlacorTask(): Boolean {
    if (chooseTaskOpen) {
        CHOOSE_TASK.dialogueContinue(ARCH_GLACOR_TASK_OPTION)
        waitThenDelayUntil(1200, 5000) { hasArchGlacorTask || !chooseTaskOpen }
        return true
    }
    val gem = inventory.getItem(BSLAY_GEM) ?: return false
    if (!gem.click("Assignment")) return false
    waitThenDelayUntil(1200, 5000) { chooseTaskOpen }
    return true
}

private val isOutside get() = Tile.of(1751, 1104, 0).withinDistance(localPlayer.tile, 20)
private val centerTile get() = tileOfLocal(34, 44, 1)
private val centerWars = Tile.of(3295, 10146, 0)
private val atWarsRetreat get() = Tile.of(3295, 10146, 0).withinDistance(localPlayer.tile, 50)

@ScriptDescription(
    name = "Arch Glacor",
    version = "1.0.0",
    author = "Trent",
    description = "Kills arch glacor"
)
class ArchGlacor : StateMachineScript<ArchGlacor>(), ConfigurableScript {
    override fun getStartState() = Init

    override fun onStart() {
        addParallelScript(CombatRotation())
        addParallelScript(AutoPrayerSwitcher())
    }
}

object Init : State<ArchGlacor>() {
    override suspend fun ArchGlacor.checkNext() = when {
        inInstancedArea -> Fight
        isOutside -> StartInstance
        else -> Bank
    }

    override suspend fun ArchGlacor.stateLoop() {}
}

object StartInstance : State<ArchGlacor>() {
    override suspend fun ArchGlacor.checkNext() = when {
        inInstancedArea -> Fight
        inventory.isFull -> Bank
        else -> null
    }

    override suspend fun ArchGlacor.stateLoop() {
        if (startOrRejoinInstance()) {
            waitThenDelayUntil(600, 10000) { !isOutside }
            return
        }
        if (interactClosestObject(ENTRANCE_PORTAL, "Enter"))
            waitThenDelayUntil(1200, 10000) { instanceStartInterfaceOpen || isDialogOpen() }
    }
}

object Fight : State<ArchGlacor>() {
    override suspend fun ArchGlacor.checkNext() = when {
        inventory.isFull -> Bank
        !inInstancedArea -> StartInstance
        else -> null
    }

    override suspend fun ArchGlacor.stateLoop() {
        if (instanceExpired && interactClosestObject(EXIT_PORTAL, "Exit")) {
            delayUntil(10000) { isOutside }
            return
        }
        if (!hasCombatTarget && interactClosestNPC("Arch-Glacor","Attack")) {
            delayUntil(5000) { hasCombatTarget }
            return
        }
        if (!hasArchGlacorTask && assignArchGlacorTask())
            return
        if (!areaLootOpen && !groundItems.isEmpty() && openAreaLoot())
            delay(1000, 630)
        if (areaLootOpen && areaLootContainsHoldableItems) {
            lootAllAreaLoot()
            delay(1000, 630)
        }
        if (!centerTile.withinDistance(localPlayer.tile, 5) && walkTo(centerTile.randomizeX(1), false))
            waitThenDelayUntil(5000, timeoutMillis = random(10000L, 15000L)) { centerTile.withinDistance(localPlayer.tile, 8) }
    }
}

object Bank : State<ArchGlacor>() {
    override suspend fun ArchGlacor.checkNext() =
        if (!inventory.isFull && isOutside) StartInstance else null

    override suspend fun ArchGlacor.stateLoop() {
        if (inventory.isFull) {
            if (!atWarsRetreat) {
                castAbility(Ability.WARS_RETREAT_TELEPORT)
                delayUntil(random(5000L, 8000L)) { atWarsRetreat }
                return
            }
            if (loadLastPresetClosestBank())
                delayUntil(5000) { !inventory.isFull }
            else
                delay(1000, 250)
            return
        }
        val portal = findClosestObject("Portal (Arch-Glacor)")
        if (portal?.interact("Enter") == true)
            delayUntil(15000) { isOutside }
        else if (walkTo(centerWars.randomize(2), false))
            delay(2000, 1059)
    }
}