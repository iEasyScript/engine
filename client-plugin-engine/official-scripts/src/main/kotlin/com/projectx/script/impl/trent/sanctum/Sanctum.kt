package com.projectx.script.impl.trent.sanctum
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
import com.projectx.script.impl.trent.combatutils.optimizedNecromancyRotation
import com.projectx.util.gaussian
import com.projectx.util.random
import com.projectx.util.showNotification
import java.lang.System.currentTimeMillis

@ScriptDescription(
    name = "Sanctum",
    version = "1.0.0",
    author = "Trent",
    description = "Sanctum of rebirth"
)
class Sanctum : StateMachineScript<Sanctum>(), ConfigurableScript {
    override fun onStart() {
        addParallelScript(SanctumPrayer())
        addParallelScript(SanctumCombatRotation())
    }
    override fun getStartState() = WhereAmI
}

object WhereAmI : State<Sanctum>() {
    override suspend fun Sanctum.checkNext() = when {
        isOutside -> StartInstance
        atWarsRetreat -> Bank()
        inInstancedArea -> Fight()
        else -> null
    }

    override suspend fun Sanctum.stateLoop() {
        toggleQuickPrayers(false)
        delay(100, 150)
    }
}

private const val ENTRANCE_PORTAL = 130744
private val atWarsRetreat get() = Tile.of(3295, 10146, 0).withinDistance(localPlayer.tile, 50)
private val centerWars = Tile.of(3295, 10146, 0)
private val isOutside get() = Tile.of(1010, 9632, 0).withinDistance(localPlayer.tile, 20)

private val vermyx = intArrayOf(31098, 31114)
private val kezlam = intArrayOf(31100, 31113)
private val nakatra = intArrayOf(31103, 31111)
private val anyBoss = vermyx+kezlam+nakatra

class Bank : State<Sanctum>() {
    private var loadedPreset = false

    override suspend fun Sanctum.checkNext() = if (loadedPreset && isOutside) StartInstance else null
    override suspend fun Sanctum.stateLoop() {
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
        val portal = findClosestObject("Portal (Sanctum of Rebirth)")
        if (portal?.interact("Enter") == true)
            delayUntil(15000) { isOutside }
        else if (walkTo(centerWars.randomize(2), false))
            delay(2000, 1059)
    }

    override fun Sanctum.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.FILTERABLE && event.message.contains("Your preset is being withdrawn"))
            loadedPreset = true
    }
}

object StartInstance : State<Sanctum>() {
    override suspend fun Sanctum.checkNext() = if (inInstancedArea) Fight() else null

    override suspend fun Sanctum.stateLoop() {
        if (continueDialogueContaining("Yes.") || continueDialogueContaining("Hard mode."))
            return delayUntil(10000) { inInstancedArea }
        if (interactClosestObject(ENTRANCE_PORTAL, "Enter"))
            delayUntil(10000) { dialogueOptionVisible("Yes.") || dialogueOptionVisible("Hard mode.") }
    }
}

private val DANGER_SPOT_ANIMS = setOf(6974, 7862)

private fun inDanger(tile: Tile) = spotAnims.any {
    (DANGER_SPOT_ANIMS.contains(it.id) && tile.withinDistance(it.tile, 1))
}
val inDanger get() = inDanger(localPlayer.tile)
val safeTile get() = tileOfLocal(32, 46, 1)

class Fight : State<Sanctum>() {
    private var currentBoss: NPC? = null
        get() = field?.takeIf { it.exists() } ?: findClosestNPC { anyBoss.contains(it.id) }.also { field = it }
    private val closestSafeTile get() = currentBoss?.let { findClosestSafeTile(it.tile) }
    private var killOver = false
    private var lootedDrops = false

    override suspend fun Sanctum.checkNext() = if (lootedDrops) Bank() else null

    override suspend fun Sanctum.stateLoop() {
        if (!inInstancedArea || findClosestNPC { it.hasOption("Reclaim items") } != null) {
            showNotification("Sanctum", "You died.")
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

        val boss = currentBoss

        if (boss == null) {
            return delay(100)
        }

        if (boss.currentHealth <= 0) return delay(100)
        if (!hasCombatTarget && boss.interact("Attack")) return delayUntil(3500) { hasCombatTarget }

        toggleQuickPrayers(true)

        if (healthPercent < 60.0 && eatFood()) delay(200, 300)
        if (drinkOverload()) delayUntil(1200) { isOverloaded }
        if (prayerPoints < 150 && drinkPrayerPot()) delay(600, 300)

        if (prayerPoints < 500 && activateElvenShard()) delayUntil(400) { elvenShardCD }
        if (healthPercent < 40.0 && activateExcalibur()) delayUntil(400) { excaliburCD }

        if (boss.isCombatTarget) {
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
                boss.interact("Attack")
                delay(530, 150)
            }
        }

        delay(200, 500)
    }

    override fun Sanctum.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("Completion Time:"))
            killOver = true
    }
}

class SanctumPrayer : Script() {
    private var currentBoss: NPC? = null
        get() = field?.takeIf { it.exists() } ?: findClosestNPC { anyBoss.contains(it.id) }.also { field = it }
    private var gigaYikesAttack = 0L

    override suspend fun loop() {
        if (prayerPoints <= 0 || !inInstancedArea || Effect.STUNNED.active) return
        val boss = currentBoss ?: return
        if (boss.currentHealth <= 0) return

        var prayer = if (onCursesPrayers) Prayer.SOUL_SPLIT else Prayer.ECLIPSED_SOUL

        if (currentTimeMillis() - gigaYikesAttack < 6000L || projectiles.any { it.id == 8182 && it.lockedOnto(localPlayer) && it.tile.getDistance(localPlayer.tile) <= 5 })
            prayer = if (onCursesPrayers) Prayer.DEFLECT_MAGIC else Prayer.PROTECT_MAGIC
        if (projectiles.any { it.id == 8185 && it.lockedOnto(localPlayer) && it.tile.getDistance(localPlayer.tile) <= 5 })
            prayer = if (onCursesPrayers) Prayer.DEFLECT_RANGE else Prayer.PROTECT_RANGED

        if (!canUseProtectionPrayers)
            prayer = if (onCursesPrayers) Prayer.SOUL_SPLIT else Prayer.ECLIPSED_SOUL

        if (!prayer.active && prayer.click())
            delayUntil(1200) { prayer.active }
    }

    override fun onEvent(event: Event) {
        if (event is Chat && event.message.contains("Prepare for death"))
            gigaYikesAttack = currentTimeMillis()
    }
}

private val reduceDamageAbilities = arrayOf(Ability.DEVOTION, Ability.DEBILITATE, Ability.REFLECT)

class SanctumCombatRotation : Script() {
    private var currentBoss: NPC? = null
        get() = field?.takeIf { it.exists() } ?: findClosestNPC { anyBoss.contains(it.id) }.also { field = it }
    private var gigaYikesAttack = 0L

    override suspend fun loop() {
        if (!inInstancedArea || healthCurrent <= 0) return
        val boss = currentBoss ?: return
        if (boss.currentHealth <= 0) return

        if (reduceDamageAbilities.any { smartCast(it, 50, condition = { !Effect.DEVOTION.active && !Effect.DEBILITATE.active && !Effect.REFLECT.active && currentTimeMillis() - gigaYikesAttack < 6000L }) })
            return
        if (smartCast(Ability.RESONANCE, 0, condition = { !Effect.DEVOTION.active && !Effect.RESONANCE.active && !Effect.DEBILITATE.active && !Effect.REFLECT.active && currentTimeMillis() - gigaYikesAttack < 6000L }))
            return

        if (smartCast(Ability.FREEDOM, condition = { Effect.STUNNED.active }))
            return

        optimizedNecromancyRotation()
    }

    override fun onEvent(event: Event) {
        if (event is Chat && event.message.contains("Prepare for death"))
            gigaYikesAttack = System.currentTimeMillis()
    }
}

private fun findClosestSafeTile(target: Tile? = null): Tile? {
    if (!inDanger) return null
    val playerPos = localPlayer.tile
    val dangerZones = spotAnims
        .filter { DANGER_SPOT_ANIMS.contains(it.id) }
        .map { DangerZone(it.tile, 0) }

    val obstacles = listOf(TileArea(tile = tileOfLocal(0, 46, 1), sizeX = 100, sizeY = 40))
    return calculateClosestSafeTile(playerPos, dangerZones, target?.let { TileArea(it, 1, 1) } ?: TileArea(playerPos, 1, 1), obstacles, checkObstacleLos = false)
}