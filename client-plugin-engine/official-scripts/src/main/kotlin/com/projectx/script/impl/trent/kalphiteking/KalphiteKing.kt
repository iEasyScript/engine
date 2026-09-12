package com.projectx.script.impl.trent.kalphiteking
import com.projectx.game.tileOfLocal

import world.gregs.voidps.type.Tile
import com.projectx.game.chat.MessageType
import org.projectx.core.game.combat.Ability
import org.projectx.core.game.combat.Effect
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.*
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.gaussian
import com.projectx.util.random
import com.projectx.util.showNotification
import java.lang.System.currentTimeMillis

@ScriptDescription(
    name = "Kalphite King",
    version = "1.0.0",
    author = "Trent",
    description = "Smokes Kalphite King"
)
class KalphiteKing : StateMachineScript<KalphiteKing>(), ConfigurableScript {
    override fun onStart() {
        addParallelScript(KKPrayer())
        addParallelScript(KKCombatRotation())
    }
    override fun getStartState() = WhereAmI
}

object WhereAmI : State<KalphiteKing>() {
    override suspend fun KalphiteKing.checkNext() = when {
        isOutside -> StartInstance
        atWarsRetreat -> Bank()
        inInstancedArea -> Fight()
        else -> null
    }

    override suspend fun KalphiteKing.stateLoop() {
        toggleQuickPrayers(false)
        delay(100, 150)
    }
}

private const val ENTRANCE_PORTAL = 82014
private val atWarsRetreat get() = Tile.of(3295, 10146, 0).withinDistance(localPlayer.tile, 50)
private val centerWars = Tile.of(3295, 10146, 0)
private val isOutside get() = Tile.of(2969, 1657, 0).withinDistance(localPlayer.tile, 20)

class Bank : State<KalphiteKing>() {
    private var loadedPreset = false

    override suspend fun KalphiteKing.checkNext() = if (loadedPreset && isOutside) StartInstance else null
    override suspend fun KalphiteKing.stateLoop() {
        toggleQuickPrayers(false)
        if (!loadedPreset) {
            if (!atWarsRetreat) {
                castAbility(Ability.WARS_RETREAT_TELEPORT)
                delayUntil(random(5000L, 8000L)) { atWarsRetreat }
                return
            }
            if (loadLastPresetClosestBank())
                delayUntil(5000) { loadedPreset }
            else
                delay(1000, 250)
            return
        }
        if (prayerPercent < 97.0 && interactClosestObject("Altar of War", "Pray"))
            return delayUntil(10000) { prayerPercent > 97.0 }
        if (!Effect.BONFIRE_BOOST.active && interactClosestObject("Campfire", "Warm hands"))
            return delayUntil(10000) { Effect.BONFIRE_BOOST.active }
        if (adrenaline < 100.0) {
            val crystal = findClosestObject("Adrenaline crystal")
            if (crystal?.interact("Channel") == true)
                delayUntil(30000) { adrenaline >= 100.0 }
            else if (walkTo(centerWars.randomize(2), false))
                delay(2000, 1059)
            return
        }
        if (!Effect.FAMILIAR_SUMMONED.active && inventory.clickItem(Regex(".*(?:binding contract| pouch).*", RegexOption.IGNORE_CASE), "Summon"))
            delay(1_200, 446)
        val portal = findClosestObject("Portal (Kalphite King)")
        if (portal?.interact("Enter") == true)
            delayUntil(15000) { isOutside }
        else if (walkTo(centerWars.randomize(2), false))
            delay(2000, 1059)
    }

    override fun KalphiteKing.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.FILTERABLE && event.message.contains("Your preset is being withdrawn"))
            loadedPreset = true
    }
}

object StartInstance : State<KalphiteKing>() {
    override suspend fun KalphiteKing.checkNext() = if (inInstancedArea) Fight() else null

    override suspend fun KalphiteKing.stateLoop() {
        if (instanceStartInterfaceOpen && startInstance())
            return delayUntil(10000) { inInstancedArea }
        if (interactClosestObject(ENTRANCE_PORTAL, "Enter"))
            delayUntil(10000) { instanceStartInterfaceOpen }
    }
}

private val DANGER_PROJECTILES = setOf(3743)

private fun inDanger(tile: Tile) = spotAnims.any {
    (DANGER_PROJECTILES.contains(it.id) && tile.withinDistance(it.tile, 2))
}
private val inDanger get() = inDanger(localPlayer.tile)
val safeTile get() = tileOfLocal(32, 46, 1)

class Fight : State<KalphiteKing>() {
    private var kk: NPC? = null
        get() = field?.takeIf { it.exists() } ?: findClosestNPC("Kalphite King").also { field = it }
    private val closestSafeTile get() = kk?.let { findClosestSafeTile(it.tile) }
    private var killOver = false
    private var lootedDrops = false

    override suspend fun KalphiteKing.checkNext() = if (lootedDrops) Bank() else null

    override suspend fun KalphiteKing.stateLoop() {
        if (!inInstancedArea || findClosestNPC { it.hasOption("Reclaim items") } != null) {
            showNotification("Kalphite King", "You died.")
            stop()
            return delay(100)
        }
        if (killOver) {
            if (safeTile.getDistance(localPlayer.tile) > 4) {
                walkTo(safeTile.randomize(1), false)
                delayUntil(gaussian(2560L, 1000L)) { safeTile.getDistance(localPlayer.tile) < 4 }
            }
            delayUntil(gaussian(4000L, 1000L)) { groundItems.isNotEmpty() }
            delay(1000, 600)
            if (groundItems.isNotEmpty()) {
                openAreaLoot()
                delayUntil(gaussian(3000L, 1000L)) { areaLootOpen }
                lootAllAreaLoot()
                delayUntil(gaussian(3000L, 1000L)) { groundItems.isEmpty() }
            }
            lootedDrops = true
            return delay(100)
        }

        val kk = kk ?: return delay(100)

        if (kk.currentHealth <= 0) return delay(100)
        if (!hasCombatTarget && kk.interact("Attack")) return delayUntil(3500) { hasCombatTarget }

        toggleQuickPrayers(true)

        if (healthPercent < 60.0 && eatFood()) delay(200, 300)
        if (drinkOverload()) delayUntil(1200) { isOverloaded }
        if (prayerPoints < 150 && drinkPrayerPot()) delay(600, 300)

        if (prayerPoints < 500 && activateElvenShard()) delayUntil(400) { elvenShardCD }
        if (healthPercent < 40.0 && activateExcalibur()) delayUntil(400) { excaliburCD }

        if (kk.isCombatTarget) {
            if (throwVulnBomb()) delayUntil(6000) { Effect.VULNERABILITY.activeOnOpponent }
            if (deployDreadnip()) delayUntil(6000) { dreadnipActive }
        }

        if (inDanger) {
            val safeTile = closestSafeTile
            if (safeTile != null) {
                if (safeTile.getDistance(localPlayer.tile) > 3 && dive(safeTile)) {
                    delay(300, 300)
                    return
                }
                walkTo(safeTile, false)
                delay(730, 150)
                kk.interact("Attack")
                delay(530, 150)
            }
        }

        delay(200, 500)
    }

    override fun KalphiteKing.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("Completion Time:"))
            killOver = true
    }
}

class KKPrayer : Script() {
    private var lastGreen = currentTimeMillis()
    private var kk: NPC? = null
        get() = field?.takeIf { it.exists() } ?: findClosestNPC("Kalphite King").also { field = it }

    override suspend fun loop() {
        if (prayerPoints <= 0 || !inInstancedArea || Effect.STUNNED.active) return
        val kk = kk ?: return
        if (kk.currentHealth <= 0) return

        if (kk.animationId == 19464)
            lastGreen = currentTimeMillis()

        var prayer = if (onCursesPrayers) Prayer.DEFLECT_MELEE else Prayer.PROTECT_MELEE

        if (kk.id == 16697 && kk.interactingWith(localPlayer))
            prayer = if (onCursesPrayers) Prayer.DEFLECT_MELEE else Prayer.PROTECT_MELEE
        if (kk.id == 16699)
            prayer = if (onCursesPrayers) Prayer.DEFLECT_RANGE else Prayer.PROTECT_RANGED
        if (kk.id == 16698 || (kk.id == 16697 && !kk.interactingWith(localPlayer)))
            prayer = if (onCursesPrayers) Prayer.DEFLECT_MAGIC else Prayer.PROTECT_MAGIC
        if (currentTimeMillis() - lastGreen < 10000)
            prayer = if (onCursesPrayers) Prayer.DEFLECT_MELEE else Prayer.PROTECT_MELEE

        if (!prayer.active && prayer.click())
            delayUntil(1200) { prayer.active }
    }
}

class KKCombatRotation : Script() {
    private var lastGreen = currentTimeMillis()
    private var kk: NPC? = null
        get() = field?.takeIf { it.exists() } ?: findClosestNPC("Kalphite King").also { field = it }

    override suspend fun loop() {
        if (!inInstancedArea || healthCurrent <= 0) return
        val kk = kk ?: return
        if (kk.currentHealth <= 0) return

        if (kk.animationId == 19464)
            lastGreen = currentTimeMillis()

        if (currentTimeMillis() - lastGreen < 10000) {
            if (!kk.interactingWith(localPlayer)) {
                castAbility(Ability.PROVOKE)
                delayUntil { kk.interactingWith(localPlayer) }
                return
            }
            if (Effect.RESONANCE.notActive && Effect.DEVOTION.notActive) {
                if (castWithAdren(Ability.DEVOTION, 50)) return
                castAndWaitForCd(Ability.RESONANCE)
            }
            return
        }
    }

    override fun onEvent(event: Event) {
    }
}

private fun findClosestSafeTile(target: Tile? = null): Tile? {
    if (!inDanger) return null
    val playerPos = localPlayer.tile
    val dangerZones = projectiles
        .filter { DANGER_PROJECTILES.contains(it.id) }
        .map { DangerZone(it.tile, 0) }

    val obstacles = listOf(TileArea(tile = tileOfLocal(0, 46, 1), sizeX = 100, sizeY = 40))
    return calculateClosestSafeTile(playerPos, dangerZones, target?.let { TileArea(it, 1, 1) } ?: TileArea(playerPos, 1, 1), obstacles, checkObstacleLos = false)
}