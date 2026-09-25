package com.projectx.script.api

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.util.gaussian
import org.projectx.core.game.combat.Ability
import java.util.concurrent.ThreadLocalRandom
import java.util.function.BooleanSupplier
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** The stops of a War's Retreat trip, in the order [WarsRetreatTrip.order] runs them. */
enum class WarsRetreatTask { BANK, ALTAR, CRYSTAL, CONJURES, PREBUILD, PORTAL }

/** War's Retreat, the boss lobby: where it is and how to get there. */
object WarsRetreat {
    const val TELEPORT = "War's Retreat Teleport"
    const val BANK_CHEST = 114750
    const val ALTAR_OF_WAR = 114748
    const val ADRENALINE_CRYSTAL = 114749
    const val TRAINING_DUMMY = 16027

    private const val CENTRE_X = 3295
    private const val CENTRE_Y = 10137
    private const val RADIUS = 30

    /** Whether the player is inside War's Retreat. */
    @JvmStatic
    val isHere: Boolean
        get() = localPlayer.plane == 0 && hypot((localPlayer.tileX - CENTRE_X).toDouble(), (localPlayer.tileY - CENTRE_Y).toDouble()) <= RADIUS

    /** Casts the War's Retreat Teleport from the action bar. */
    @JvmStatic
    fun teleport(): Boolean = abilityUsable(TELEPORT) && castAbility(TELEPORT)
}

/**
 * One visit to War's Retreat between kills: [order] lists the stops, and each runs only when it is enabled and
 * still needed. The bank loads the last preset, the altar restores prayer, the crystal fills adrenaline, conjures
 * are summoned, [prebuild] runs on a training dummy until [prebuildDone], and the trip ends through the boss portal
 * named [portalName]. When the bank asks for a PIN, [bankPin] is entered.
 *
 * With [advancedMovement], the long walks are cut short the way players do it: a Dive from the teleport arrival
 * to the bank, and a Surge then Dive from the bank north to the crystal or portal, each taken [advancedMovementChance]
 * percent of the time. Surge and a Dive (or Bladed Dive) must be on an action bar.
 */
class WarsRetreatTrip @JvmOverloads constructor(
    val portalName: String?,
    var loadPreset: Boolean = true,
    var prayAtAltar: Boolean = true,
    var useAdrenalineCrystal: Boolean = true,
    var summonConjures: Boolean = false,
    var prebuild: List<RotationStep>? = null,
    var prebuildDone: BooleanSupplier? = null,
    var waitForFullHealth: Boolean = true,
    var order: List<WarsRetreatTask> = WarsRetreatTask.entries,
    var bankPin: String? = null,
) {
    var advancedMovement: Boolean = false
    var advancedMovementChance: Int = 100

    var lastAction: String = ""
        internal set
}

/**
 * Runs [trip]: teleports to War's Retreat if needed, visits each stop, and enters the boss portal. Every stop waits
 * on its own outcome (preset loaded, prayer full, adrenaline full, conjures up, the lobby left behind) and moves on
 * the moment it lands. Returns true once the player has gone through the portal, or has finished the stops when the
 * trip has no portal.
 */
suspend fun Script.runWarsRetreatTrip(trip: WarsRetreatTrip): Boolean {
    if (!WarsRetreat.isHere) {
        trip.lastAction = "Teleporting to War's Retreat"
        if (!WarsRetreat.teleport()) return false
        delayUntil(gaussian(9000L, 1800L), POLL_MILLIS) { WarsRetreat.isHere && !localPlayer.isMoving }
        if (!WarsRetreat.isHere) return false
        settle()
    }
    for (task in trip.order) {
        val done = when (task) {
            WarsRetreatTask.BANK -> !trip.loadPreset || loadPreset(trip)
            WarsRetreatTask.ALTAR -> !trip.prayAtAltar || restorePrayer(trip)
            WarsRetreatTask.CRYSTAL -> !trip.useAdrenalineCrystal || fillAdrenaline(trip)
            WarsRetreatTask.CONJURES -> !trip.summonConjures || summonConjures(trip)
            WarsRetreatTask.PREBUILD -> trip.prebuild == null || prebuild(trip)
            WarsRetreatTask.PORTAL -> trip.portalName == null || enterPortal(trip)
        }
        if (!done) return false
    }
    return true
}

private const val POLL_MILLIS = 30

private suspend fun Script.settle() = delay(gaussian(190, 70))

private fun stationWithin(id: Int, range: Int = 60): SceneObject? = findClosestObject(id, range)

private fun wornAndCarried(): List<Pair<Int, Int>> = inventory.map { it.id to it.amount } + equipment.map { it.id to it.amount }

private suspend fun Script.loadPreset(trip: WarsRetreatTrip): Boolean {
    trip.lastAction = "Loading last preset"
    val chest = stationWithin(WarsRetreat.BANK_CHEST) ?: return false
    if (trip.advancedMovement) diveToBank(trip)
    val before = wornAndCarried()
    if (!chest.interact("Load Last Preset from")) return false
    var stillSince = -1L
    delayUntil(gaussian(7000L, 1400L), POLL_MILLIS) {
        if (BankPin.isOpen || wornAndCarried() != before) return@delayUntil true
        val atChest = !localPlayer.isMoving && chest.distanceTo(localPlayer.tileX.toDouble(), localPlayer.tileY.toDouble()) <= CHEST_REACH
        if (!atChest) {
            stillSince = -1L
            false
        } else {
            if (stillSince < 0) stillSince = ServerTick.count
            ServerTick.count - stillSince >= 1
        }
    }
    if (BankPin.isOpen) {
        val pin = trip.bankPin ?: return false
        trip.lastAction = "Entering bank PIN"
        if (!enterBankPin(pin)) return false
        settle()
        return loadPreset(trip)
    }
    settle()
    return true
}

private suspend fun Script.restorePrayer(trip: WarsRetreatTrip): Boolean {
    if (prayerPoints >= prayerMax) return true
    trip.lastAction = "Praying at the Altar of War"
    val altar = stationWithin(WarsRetreat.ALTAR_OF_WAR) ?: return false
    if (!altar.interact("Pray")) return false
    delayUntil(gaussian(7000L, 1400L), POLL_MILLIS) { prayerPoints >= prayerMax }
    settle()
    return prayerPoints >= prayerMax
}

private suspend fun Script.fillAdrenaline(trip: WarsRetreatTrip): Boolean {
    if (adrenaline >= FULL_ADRENALINE) return true
    trip.lastAction = "Channelling the adrenaline crystal"
    val crystal = stationWithin(WarsRetreat.ADRENALINE_CRYSTAL) ?: return true
    if (trip.advancedMovement) moveNorth(trip, crystalApproach(trip))
    if (!crystal.interact("Channel")) return false
    delayUntil(gaussian(14000L, 2400L), POLL_MILLIS) { adrenaline >= FULL_ADRENALINE }
    return true
}

private suspend fun Script.summonConjures(trip: WarsRetreatTrip): Boolean {
    val summoned = { conjuresUp() }
    if (summoned()) return true
    trip.lastAction = "Conjuring the undead army"
    if (!(abilityUsable(CONJURE_ARMY) && castAbility(CONJURE_ARMY))) return true
    delayUntil(gaussian(6000L, 1200L), POLL_MILLIS) { summoned() }
    settle()
    return true
}

private fun conjuresUp(): Boolean =
    listOf("Skeleton Warrior", "Vengeful Ghost", "Putrid Zombie").all { effectNamed(it)?.active() == true }

private suspend fun Script.prebuild(trip: WarsRetreatTrip): Boolean {
    val steps = trip.prebuild ?: return true
    trip.lastAction = "Prebuilding"
    val rotation = RotationManager().apply { load(steps) }
    val done = trip.prebuildDone
    val deadline = System.currentTimeMillis() + gaussian(45_000L, 6000L)
    while (!rotation.isFinished && done?.asBoolean != true && System.currentTimeMillis() < deadline) {
        rotation.execute()
        delay(gaussian(90, 30))
    }
    return true
}

private suspend fun Script.enterPortal(trip: WarsRetreatTrip): Boolean {
    if (trip.waitForFullHealth && healthCurrent < healthMax) {
        trip.lastAction = "Waiting for full health"
        delayUntil(gaussian(60_000L, 8000L), POLL_MILLIS) { healthCurrent >= healthMax }
    }
    trip.lastAction = "Entering ${trip.portalName}"
    val portal = findClosestObject(trip.portalName ?: return true, 60) ?: return false
    if (trip.advancedMovement) moveNorth(trip, portalApproach(portal))
    if (!portal.interact("Enter")) return false
    delayUntil(gaussian(12000L, 2000L), POLL_MILLIS) { !WarsRetreat.isHere }
    return !WarsRetreat.isHere
}

private fun takeShortcut(trip: WarsRetreatTrip): Boolean =
    ThreadLocalRandom.current().nextInt(100) < trip.advancedMovementChance.coerceIn(0, 100)

private fun near(x: Int, y: Int, within: Int) =
    max(abs(localPlayer.tileX - x), abs(localPlayer.tileY - y)) <= within

private suspend fun Script.diveToBank(trip: WarsRetreatTrip) {
    if (!near(ARRIVAL_X, ARRIVAL_Y, 2) || !isDiveReady() || !takeShortcut(trip)) return
    trip.lastAction = "Diving to the bank"
    if (diveToTile(BANK_STAND_X, BANK_STAND_Y)) {
        delayUntil(gaussian(1800L, 400L), POLL_MILLIS) { near(BANK_STAND_X, BANK_STAND_Y, 2) }
        settle()
    }
}

/**
 * From the bank area north to a station: step onto the bank-chest tile from the south, which leaves the player
 * facing north, then Surge up the courtyard and Dive the rest of the way to [target]. Anywhere else, or with Surge
 * or Dive on cooldown, it leaves the walk to the station's own interaction.
 */
private suspend fun Script.moveNorth(trip: WarsRetreatTrip, target: Pair<Int, Int>) {
    if (localPlayer.tileY >= NORTH_OF_BANK || !near(BANK_STAND_X, BANK_STAND_Y, 8)) return
    if (!Ability.SURGE.offCdIgnoreGCD || !isDiveReady() || !takeShortcut(trip)) return
    trip.lastAction = "Surge and dive north"
    if (!near(BANK_STAND_X, BANK_STAND_Y, 0)) {
        if (!near(BANK_STAND_X, BANK_STAND_Y - 1, 0)) {
            walkToTile(BANK_STAND_X, BANK_STAND_Y - 1)
            delayUntil(gaussian(4000L, 800L), POLL_MILLIS) { near(BANK_STAND_X, BANK_STAND_Y - 1, 0) && !localPlayer.isMoving }
        }
        walkToTile(BANK_STAND_X, BANK_STAND_Y)
        delayUntil(gaussian(1800L, 400L), POLL_MILLIS) { near(BANK_STAND_X, BANK_STAND_Y, 0) && !localPlayer.isMoving }
        if (!near(BANK_STAND_X, BANK_STAND_Y, 0)) return
    }
    val startY = localPlayer.tileY
    if (!surge()) return
    delayUntil(gaussian(1200L, 300L), POLL_MILLIS) { localPlayer.tileY - startY >= SURGE_PROGRESS }
    delay(gaussian(120, 50))
    if (diveToTile(target.first, target.second))
        delayUntil(gaussian(1800L, 400L), POLL_MILLIS) { near(target.first, target.second, 2) }
    settle()
}

private fun crystalApproach(trip: WarsRetreatTrip): Pair<Int, Int> {
    val portal = trip.portalName?.let { findClosestObject(it, 60) }
    return if (portal != null && portal.tileX >= EAST_PORTAL_X) CRYSTAL_EAST else CRYSTAL_WEST
}

private fun portalApproach(portal: SceneObject): Pair<Int, Int> =
    if (portal.tileX >= EAST_PORTAL_X) PORTAL_EAST else PORTAL_WEST

private const val FULL_ADRENALINE = 100.0
private const val CONJURE_ARMY = "Conjure Undead Army"
private const val CHEST_REACH = 2.5
private const val ARRIVAL_X = 3294
private const val ARRIVAL_Y = 10127
private const val BANK_STAND_X = 3299
private const val BANK_STAND_Y = 10131
private const val NORTH_OF_BANK = 10140
private const val SURGE_PROGRESS = 5
private const val EAST_PORTAL_X = 3296
private val CRYSTAL_WEST = 3290 to 10148
private val CRYSTAL_EAST = 3298 to 10148
private val PORTAL_WEST = 3290 to 10153
private val PORTAL_EAST = 3298 to 10153
