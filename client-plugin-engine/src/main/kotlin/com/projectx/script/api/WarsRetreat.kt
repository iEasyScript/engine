package com.projectx.script.api

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.util.gaussian
import java.util.function.BooleanSupplier
import kotlin.math.hypot

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
    var lastAction: String = ""
        internal set
}

/**
 * Runs [trip]: teleports to War's Retreat if needed, visits each stop, and enters the boss portal. Every stop waits
 * on its own outcome (prayer full, adrenaline full, conjures up, the lobby left behind). Returns true once the
 * player has gone through the portal, or has finished the stops when the trip has no portal.
 */
suspend fun Script.runWarsRetreatTrip(trip: WarsRetreatTrip): Boolean {
    if (!WarsRetreat.isHere) {
        trip.lastAction = "Teleporting to War's Retreat"
        if (!WarsRetreat.teleport()) return false
        delayUntil(gaussian(9000L, 1800L)) { WarsRetreat.isHere && !localPlayer.isMoving }
        if (!WarsRetreat.isHere) return false
        delay(gaussian(640, 220))
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

private fun stationWithin(id: Int, range: Int = 60): SceneObject? = findClosestObject(id, range)

private suspend fun Script.loadPreset(trip: WarsRetreatTrip): Boolean {
    trip.lastAction = "Loading last preset"
    val chest = stationWithin(WarsRetreat.BANK_CHEST) ?: return false
    val before = inventory.map { it.id to it.amount }
    if (!chest.interact("Load Last Preset from")) return false
    delayUntil(gaussian(7000L, 1400L)) { BankPin.isOpen || inventory.map { it.id to it.amount } != before && !localPlayer.isMoving }
    if (BankPin.isOpen) {
        val pin = trip.bankPin ?: return false
        trip.lastAction = "Entering bank PIN"
        if (!enterBankPin(pin)) return false
        delay(gaussian(600, 200))
        return loadPreset(trip)
    }
    delay(gaussian(520, 180))
    return true
}

private suspend fun Script.restorePrayer(trip: WarsRetreatTrip): Boolean {
    if (prayerPoints >= prayerMax) return true
    trip.lastAction = "Praying at the Altar of War"
    val altar = stationWithin(WarsRetreat.ALTAR_OF_WAR) ?: return false
    if (!altar.interact("Pray")) return false
    delayUntil(gaussian(7000L, 1400L)) { prayerPoints >= prayerMax }
    delay(gaussian(480, 160))
    return prayerPoints >= prayerMax
}

private suspend fun Script.fillAdrenaline(trip: WarsRetreatTrip): Boolean {
    if (adrenaline >= FULL_ADRENALINE) return true
    trip.lastAction = "Channelling the adrenaline crystal"
    val crystal = stationWithin(WarsRetreat.ADRENALINE_CRYSTAL) ?: return true
    if (!crystal.interact("Channel")) return false
    delayUntil(gaussian(14000L, 2400L)) { adrenaline >= FULL_ADRENALINE }
    delay(gaussian(420, 160))
    return true
}

private suspend fun Script.summonConjures(trip: WarsRetreatTrip): Boolean {
    val summoned = { conjuresUp() }
    if (summoned()) return true
    trip.lastAction = "Conjuring the undead army"
    if (!(abilityUsable(CONJURE_ARMY) && castAbility(CONJURE_ARMY))) return true
    delayUntil(gaussian(6000L, 1200L)) { summoned() }
    delay(gaussian(460, 160))
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
        delayUntil(gaussian(60_000L, 8000L)) { healthCurrent >= healthMax }
    }
    trip.lastAction = "Entering ${trip.portalName}"
    val portal = findClosestObject(trip.portalName ?: return true, 60) ?: return false
    if (!portal.interact("Enter")) return false
    delayUntil(gaussian(12000L, 2000L)) { !WarsRetreat.isHere }
    return !WarsRetreat.isHere
}

private const val FULL_ADRENALINE = 100.0
private const val CONJURE_ARMY = "Conjure Undead Army"
