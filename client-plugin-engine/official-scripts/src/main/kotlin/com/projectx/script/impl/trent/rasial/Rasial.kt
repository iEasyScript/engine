package com.projectx.script.impl.trent.rasial
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

@ScriptDescription(
    name = "Rasial",
    version = "1.0.0",
    author = "Trent",
    description = "Gigachads out Rasial kills with any gear you want."
)
class Rasial : StateMachineScript<Rasial>(), ConfigurableScript {
    override fun onStart() {
        addParallelScript(RasialPrayer())
        addParallelScript(RasialCombatRotation())
    }
    override fun getStartState() = WhereAmI
}

object WhereAmI : State<Rasial>() {
    override suspend fun Rasial.checkNext() = when {
        isOutside -> StartInstance
        atWarsRetreat -> Bank()
        inInstancedArea -> Fight()
        else -> null
    }

    override suspend fun Rasial.stateLoop() {
        toggleQuickPrayers(false)
        delay(100, 150)
    }
}

private const val ENTRANCE_PORTAL = 127142
private val atWarsRetreat get() = Tile.of(3295, 10146, 0).withinDistance(localPlayer.tile, 50)
private val centerWars = Tile.of(3295, 10146, 0)
private val isOutside get() = Tile.of(864, 1742, 1).withinDistance(localPlayer.tile, 20)

class Bank : State<Rasial>() {
    private var loadedPreset = false

    override suspend fun Rasial.checkNext() = if (loadedPreset && isOutside) StartInstance else null
    override suspend fun Rasial.stateLoop() {
        toggleQuickPrayers(false)
        if (!loadedPreset) {
            if (!atWarsRetreat) {
                castAbility(Ability.WARS_RETREAT_TELEPORT)
                delayUntil(random(5000L, 8000L)) { atWarsRetreat }
                return
            }
            if (loadLastPresetClosestBank())
                delayUntil(5000) { loadedPreset && healthPercent >= 100.0 }
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
        val portal = findClosestObject("Portal (Rasial's Citadel)")
        if (portal?.interact("Enter") == true)
            delayUntil(15000) { isOutside }
        else if (walkTo(centerWars.randomize(2), false))
            delay(2000, 1059)
    }

    override fun Rasial.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.FILTERABLE && event.message.contains("Your preset is being withdrawn"))
            loadedPreset = true
    }
}

object StartInstance : State<Rasial>() {
    override suspend fun Rasial.checkNext() = if (inInstancedArea) Fight() else null

    override suspend fun Rasial.stateLoop() {
        if (instanceStartInterfaceOpen && startInstance())
            return delayUntil(10000) { inInstancedArea }
        if (continueDialogueContaining("Rasial, the First Necromancer")) {
            delayUntil(10000) { instanceStartInterfaceOpen }
            return
        }
        if (interactClosestObject(ENTRANCE_PORTAL, "Enter"))
            delayUntil(10000) { instanceStartInterfaceOpen || dialogueOptionVisible("Rasial, the First Necromancer") }
    }
}

private val DANGER_SPOT_ANIMS = setOf(6974, 7862)

private fun inDanger(tile: Tile) = spotAnims.any {
    (DANGER_SPOT_ANIMS.contains(it.id) && tile.withinDistance(it.tile, 1))
}
private val inDanger get() = inDanger(localPlayer.tile)
val safeTile get() = tileOfLocal(32, 46, 1)

private val isWearingDeathdealerArmor get() = equipment.any {
    it.name.contains("Deathdealer", ignoreCase = true)
}

class Fight : State<Rasial>() {
    private var currentRasial: NPC? = null
        get() = field?.takeIf { it.exists() } ?: findClosestNPC("Rasial, the First Necromancer").also { field = it }
    private val closestSafeTile get() = currentRasial?.let { findClosestSafeTile(it.tile) }
    private var killOver = false
    private var lootedDrops = false

    override suspend fun Rasial.checkNext() = if (lootedDrops) Bank() else null

    override suspend fun Rasial.stateLoop() {
        if (!inInstancedArea || findClosestNPC { it.hasOption("Reclaim items") } != null) {
            showNotification("Rasial", "You died.")
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

        val rasial = currentRasial

        if (rasial == null) {
            if (!safeTile.matches(localPlayer.tile)) {
                if (dive(localPlayer.tile.transform(0, 11)))
                    delay(125, 100)
                surge()
                delay(125, 100)
                if (walkTo(safeTile, false)) {
                    while (!localPlayer.isMoving && !safeTile.matches(localPlayer.tile)) {
                        delay(352, 100)
                        walkTo(safeTile, false)
                        delay(300, 300)
                    }
                    if (!isWearingDeathdealerArmor && smartCast(ability = Ability.INVOKE_DEATH)) {
                        delay(600, 100)
                    }
                    waitThenDelayUntil(1000, 10000) { !localPlayer.isAniMoving }
                } else
                    delay(100)
            } else if (smartCast(ability = Ability.CONJURE_UNDEAD_ARMY, condition = { !Effect.SKELETON_WARRIOR.active }, waitCondition = { Effect.SKELETON_WARRIOR.active }))
                delay(600, 100)
            else if (smartCast(ability = Ability.COMMAND_VENGEFUL_GHOST, condition = { Effect.VENGEFUL_GHOST.active && !Effect.VENGEFUL_GHOST_HAUNT.active }, waitCondition = { Effect.VENGEFUL_GHOST_HAUNT.active }))
                delay(600, 100)
            return delay(100)
        }

        if (rasial.currentHealth <= 0) return delay(100)
        if (!hasCombatTarget && rasial.interact("Attack")) return delayUntil(3500) { hasCombatTarget }

        toggleQuickPrayers(true)

        if (healthPercent < 60.0) {
            if (eatFood()) delay(200, 150)
            if (inventory.clickItem(Regex(".*Saradomin brew.*", RegexOption.IGNORE_CASE), "Drink"))
                delay(150, 150)
        }
        if (drinkOverload()) delayUntil(1200) { isOverloaded }
        if (prayerPoints < 150 && drinkPrayerPot()) delay(600, 300)

        if (prayerPoints < 500 && activateElvenShard()) delayUntil(400) { elvenShardCD }
        if (healthPercent < 40.0 && activateExcalibur()) delayUntil(400) { excaliburCD }

        if (rasial.isCombatTarget) {
            if (throwVulnBomb()) delayUntil(6000) { Effect.VULNERABILITY.activeOnOpponent }
            if (deployDreadnip()) delayUntil(6000) { dreadnipActive }
        }
        if (rasial.currentHealth <= rasial.maxHealth * 0.10) {
            val lotdRegex = Regex(".*Luck of the Dwarves.*", RegexOption.IGNORE_CASE)
            if (inventory.hasItem(lotdRegex) && inventory.clickItem(lotdRegex, "Wear"))
                delay(600, 300)
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
                rasial.interact("Attack")
                delay(530, 150)
            }
        }

        delay(200, 500)
    }

    override fun Rasial.onStateEvent(event: Event) {
        if (event is Chat && event.messageType == MessageType.UNFILTERABLE && event.message.contains("Completion Time:"))
            killOver = true
    }
}

private object Anim {
    const val SPAWN = 23870
    const val MAINHAND_AUTO_ATTACK = 35632
    const val DEATH_SPARK = 35633
    const val OFFHAND_AUTO_ATTACK = 35634
    const val FINGER_OF_DEATH = 35635
    const val TOUCH_OF_DEATH = 35636
    const val SOUL_SAP = 35637
    const val STUN_AND_DISABLE_PRAYERS = 35640
    const val DIVES_TO_YOU = 35641
    const val DIVES_AWAY_FROM_YOU = 35642
    const val UNDEAD_CONJURES = 35644
    const val VOLLEY_OF_SOULS = 35469
    const val SPECTRAL_SCYTHE = 35489
    const val GHOST_WALL = 35501
}

private val rasialPrayAgainstAnims = setOf(
    Anim.MAINHAND_AUTO_ATTACK,
    Anim.OFFHAND_AUTO_ATTACK,
    Anim.DEATH_SPARK,
    Anim.FINGER_OF_DEATH,
    Anim.TOUCH_OF_DEATH,
    Anim.SOUL_SAP,
    Anim.VOLLEY_OF_SOULS,
    Anim.SPECTRAL_SCYTHE
)

class RasialPrayer : Script() {
    private var currentRasial: NPC? = null
        get() = field?.takeIf { it.exists() } ?: findClosestNPC("Rasial, the First Necromancer").also { field = it }

    override suspend fun loop() {
        if (prayerPoints <= 0 || !inInstancedArea || Effect.STUNNED.active) return
        val rasial = currentRasial ?: return
        if (rasial.currentHealth <= 0) return

        var prayer = if (onCursesPrayers) Prayer.SOUL_SPLIT else Prayer.ECLIPSED_SOUL

        if (rasialPrayAgainstAnims.contains(rasial.animationId) && rasial.currentHealth > 200000)
            prayer = if (onCursesPrayers) Prayer.DEFLECT_NECROMANCY else Prayer.PROTECT_NECROMANCY

        if (!canUseProtectionPrayers)
            prayer = if (onCursesPrayers) Prayer.SOUL_SPLIT else Prayer.ECLIPSED_SOUL

        if (!prayer.active && prayer.click())
            delayUntil(1200) { prayer.active }
    }
}

private val reduceDamageAbilities = arrayOf(Ability.DEVOTION, Ability.DEBILITATE, Ability.REFLECT)

class RasialCombatRotation : Script() {
    private var currentRasial: NPC? = null
        get() = field?.takeIf { it.exists() } ?: findClosestNPC("Rasial, the First Necromancer").also { field = it }
    private var gigaYikesAttack = 0L

    override suspend fun loop() {
        if (!inInstancedArea || healthCurrent <= 0) return
        val rasial = currentRasial ?: return
        if (rasial.currentHealth <= 0) return

        if (smartCast(Ability.DEBILITATE, 50, condition = { Effect.DEBILITATE.notActive && rasial.currentHealth <= 200000 }))
            return

        if (reduceDamageAbilities.any { smartCast(it, 50, condition = { rasial.currentHealth > 200000 && Effect.RESIDUAL_SOUL_OPPONENT.stacks >= 5 }) })
            return

        if (smartCast(Ability.FREEDOM, condition = { Effect.STUNNED.active || rasial.animationId == Anim.STUN_AND_DISABLE_PRAYERS || rasial.animationId == Anim.DIVES_TO_YOU }))
            return

        optimizedNecromancyRotation()
    }

    override fun onEvent(event: Event) {
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