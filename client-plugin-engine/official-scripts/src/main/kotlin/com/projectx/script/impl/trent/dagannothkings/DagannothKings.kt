package com.projectx.script.impl.trent.dagannothkings
import com.projectx.game.tileOfLocal

import world.gregs.voidps.type.Tile
import com.projectx.game.chat.MessageType
import org.projectx.core.game.combat.Ability
import com.projectx.game.interfaces.InstanceSystem
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.ConfigurableScript
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.random

private const val DK_PORTAL = "Portal (Dagannoth Kings)"
private const val LADDER = 10230

private const val REX_ATTACK_RANGE = 7

private const val KING_SAFE_DISTANCE = 9

private val PRIORITY_LOOT =
    Regex(".*((berserker|warrior|archers'?|seers'?) ring|dragon hatchet).*", RegexOption.IGNORE_CASE)

private val warsRetreat = Tile.of(3295, 10146, 0)
private val atWarsRetreat get() = warsRetreat.withinDistance(localPlayer.tile, 50)

private val aggroTile get() = tileOfLocal(44, 22, localPlayer.tile.plane)
private val safeTile get() = tileOfLocal(48, 27, localPlayer.tile.plane)

private val REX_IDS = intArrayOf(2883, 30977)
private val SUPREME_IDS = intArrayOf(2881, 30975)
private val PRIME_IDS = intArrayOf(2882, 30976)

private val rex get() = findClosestNPC(40) { it.id in REX_IDS }
private val supreme get() = findClosestNPC(40) { it.id in SUPREME_IDS }
private val prime get() = findClosestNPC(40) { it.id in PRIME_IDS }

private val supremeOrPrimeOnUs
    get() = supreme?.interactingWith(localPlayer) == true || prime?.interactingWith(localPlayer) == true

private val kingsClear: Boolean
    get() {
        val sup = supreme
        if (sup != null && sup.tile.getDistance(localPlayer.tile) <= KING_SAFE_DISTANCE) return false
        val pri = prime
        if (pri != null && pri.tile.getDistance(localPlayer.tile) <= KING_SAFE_DISTANCE) return false
        return true
    }

@ScriptDescription(
    name = "Dagannoth Kings",
    version = "1.0.0",
    author = "Trent",
    description = "Safespots and kills Dagannoth Rex, loots every drop (rings/hatchet first), banks if Supreme/Prime aggro"
)
class DagannothKings : StateMachineScript<DagannothKings>(), ConfigurableScript {
    var lootDone = false
    override fun getStartState() = WhereAmI
}

object WhereAmI : State<DagannothKings>() {
    override suspend fun DagannothKings.checkNext() = if (inInstancedArea) Fight() else EnterInstance()
    override suspend fun DagannothKings.stateLoop() = delay(100, 150)
}

class Fight : State<DagannothKings>() {
    private var positioned = false
    private var lured = false

    private var dagRex: NPC? = null
        get() = field?.takeIf { it.exists() } ?: rex.also { field = it }

    private val rexDead: Boolean get() = dagRex?.let { it.currentHealth <= 0 } == true
    private val atCombatArea: Boolean get() = localPlayer.tile.getDistance(aggroTile) <= 12

    override suspend fun DagannothKings.checkNext() = when {
        !inInstancedArea -> EnterInstance()
        supremeOrPrimeOnUs -> Bank()
        !lootDone && atCombatArea && (rexDead || groundItems.isNotEmpty() || (areaLootOpen && !areaLoot.isEmpty)) -> Loot()
        inventory.isFull -> Bank()
        else -> null
    }

    override suspend fun DagannothKings.stateLoop() {
        val safe = safeTile
        val aggro = aggroTile
        val rex = dagRex
        val p = localPlayer.tile

        if (rex == null || rex.currentHealth <= 0) {
            positioned = false
            lured = false
            val returnToLure = rex == null || lootDone
            if (returnToLure && p.getDistance(aggro) > 1) {
                walkTo(aggro, true)
                delayUntil(8000) { localPlayer.tile.getDistance(aggro) <= 1 || (dagRex?.let { it.currentHealth > 0 } == true) }
            }
            return delay(300, 200)
        }
        lootDone = false

        if (!positioned) {
            if (p.getDistance(aggro) > 1) {
                walkTo(aggro, true)
                delayUntil(8000) { localPlayer.tile.getDistance(aggro) <= 1 }
                return
            }
            if (lured && rex.interactingWith(localPlayer) && rex.tile.getDistance(p) <= REX_ATTACK_RANGE) {
                if (localPlayer.tile != safe) {
                    walkTo(safe, false)
                    delayUntil(5000) { localPlayer.tile == safe }
                }
                positioned = localPlayer.tile == safe
                return delay(100)
            }
            if (kingsClear) {
                if (!localPlayer.interactingWith(rex)) rex.interact("Attack")
                lured = lured || localPlayer.interactingWith(rex)
            }
            return delay(150, 100)
        }

        if (localPlayer.tile != safe) {
            walkTo(safe, false)
            delayUntil(3000) { localPlayer.tile == safe }
            return delay(100)
        }
        if (!localPlayer.interactingWith(rex) && rex.tile.getDistance(localPlayer.tile) <= REX_ATTACK_RANGE)
            rex.interact("Attack")
        delay(400, 300)
    }
}

class Loot : State<DagannothKings>() {
    private var bankNow = false
    private var openTries = 0

    override suspend fun DagannothKings.checkNext() = when {
        !inInstancedArea -> EnterInstance()
        supremeOrPrimeOnUs -> Bank()
        bankNow -> Bank()
        lootDone -> Fight()
        else -> null
    }

    override suspend fun DagannothKings.stateLoop() {
        if (!areaLootOpen) {
            openAreaLoot()
            delayUntil(1500) { areaLootOpen }
            if (!areaLootOpen && ++openTries >= 5)
                lootDone = true
            return
        }
        if (areaLoot.isEmpty) {
            lootDone = true
            return
        }

        val priority = areaLoot.firstOrNull { it.name.matches(PRIORITY_LOOT) }
        if (priority != null) {
            if (inventory.isFull) {
                eatFood()
                delayUntil(1500) { !inventory.isFull }
                if (inventory.isFull) { bankNow = true; return }
            }
            priority.click(1)
            delay(500, 300)
            return
        }

        if (inventory.isFull) { bankNow = true; return }
        lootAllAreaLoot()
        delayUntil(3000) { !areaLootOpen || areaLoot.isEmpty }
        delay(300, 200)
    }
}

class Bank : State<DagannothKings>() {
    private var loadedPreset = false

    override suspend fun DagannothKings.checkNext() =
        if (loadedPreset && healthPercent >= 100.0) EnterInstance() else null

    override suspend fun DagannothKings.stateLoop() {
        if (!atWarsRetreat) {
            castAbility(Ability.WARS_RETREAT_TELEPORT)
            delayUntil(random(5000L, 8000L)) { atWarsRetreat }
            return
        }
        if (!loadedPreset) {
            if (loadLastPresetClosestBank()) delayUntil(5000) { loadedPreset }
            else delay(1000, 250)
            return
        }
        if (healthPercent < 100.0)
            delayUntil(3000) { healthPercent >= 100.0 }
    }

    override fun DagannothKings.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.FILTERABLE && event.message.contains("Your preset is being withdrawn"))
            loadedPreset = true
    }
}

class EnterInstance : State<DagannothKings>() {
    override suspend fun DagannothKings.checkNext() = if (inInstancedArea) Fight() else null

    override suspend fun DagannothKings.stateLoop() {
        if (instanceStartInterfaceOpen) {
            if (!instanceExpired && InstanceSystem.rejoinInstance()) {
                delayUntil(8000) { inInstancedArea }
                if (inInstancedArea) return
            }
            if (startInstance()) delayUntil(10000) { inInstancedArea }
            else delay(800, 200)
            return
        }
        val ladder = findClosestObject(20) { it.id == LADDER }
        if (ladder != null) {
            if (ladder.interact("Private encounter")) delayUntil(8000) { instanceStartInterfaceOpen || inInstancedArea }
            else delay(600, 200)
            return
        }
        if (atWarsRetreat) {
            if (findClosestObject(DK_PORTAL)?.interact("Enter") == true)
                delayUntil(10000) { findClosestObject(20) { it.id == LADDER } != null || inInstancedArea }
            else if (walkTo(warsRetreat.randomize(2), false))
                delay(1500, 500)
            return
        }
        castAbility(Ability.WARS_RETREAT_TELEPORT)
        delayUntil(random(5000L, 8000L)) { atWarsRetreat }
    }
}
