package com.projectx.script.api

import java.util.function.BooleanSupplier

/**
 * Something that calls for a protection prayer: a projectile in flight, an NPC animation, or any condition. The
 * flicker switches to [prayer] [delayTicks] ticks after the threat appears and keeps it up until [durationTicks]
 * ticks after it is gone. When several threats are live, the highest [priority] wins.
 */
class PrayerThreat private constructor(
    val name: String,
    val prayer: Prayer,
    val priority: Int,
    val delayTicks: Int,
    val durationTicks: Int,
    private val detect: () -> Boolean,
    private val bypass: BooleanSupplier?,
) {
    /** Outranks threats with a lower [priority] when both are live. */
    fun priority(priority: Int) = copy(priority = priority)

    /** Waits [ticks] server ticks after the threat appears before switching. */
    fun delayTicks(ticks: Int) = copy(delayTicks = ticks)

    /** Keeps the prayer up for [ticks] server ticks after the threat is gone. */
    fun durationTicks(ticks: Int) = copy(durationTicks = ticks)

    /** Ignores the threat whenever [bypass] holds. */
    fun unless(bypass: BooleanSupplier) = copy(bypass = bypass)

    internal fun present(): Boolean = detect() && bypass?.asBoolean != true

    private fun copy(
        priority: Int = this.priority,
        delayTicks: Int = this.delayTicks,
        durationTicks: Int = this.durationTicks,
        bypass: BooleanSupplier? = this.bypass,
    ) = PrayerThreat(name, prayer, priority, delayTicks, durationTicks, detect, bypass)

    companion object {
        private const val DEFAULT_RANGE = 60

        /** A projectile with id [projectileId] within [range] tiles of the player. */
        @JvmStatic
        @JvmOverloads
        fun projectile(name: String, prayer: Prayer, projectileId: Int, range: Int = DEFAULT_RANGE) =
            PrayerThreat(name, prayer, 0, 0, 0, {
                projectiles.any { it.id == projectileId && playerDistanceTo(it.tileX.toDouble(), it.tileY.toDouble()) <= range }
            }, null)

        /** An NPC with id [npcId] within [range] tiles playing any of [animationIds]. */
        @JvmStatic
        fun animation(name: String, prayer: Prayer, npcId: Int, range: Int, vararg animationIds: Int) =
            PrayerThreat(name, prayer, 0, 0, 0, {
                allNpcsWithinRange(range) { it.id == npcId && it.animationId in animationIds }.isNotEmpty()
            }, null)

        /** Whatever [condition] says. */
        @JvmStatic
        fun condition(name: String, prayer: Prayer, condition: BooleanSupplier) =
            PrayerThreat(name, prayer, 0, 0, 0, { condition.asBoolean }, null)
    }
}

/**
 * Keeps the right protection prayer up, tick by tick: [defaultPrayer] while nothing threatens, and the prayer of the
 * highest-priority live [threats] otherwise. Call [update] every loop. It toggles prayers from the action bar, so
 * every prayer it may use must be on one; [missingPrayers] names any that are not.
 *
 * It switches at most once a tick, and waits four ticks before re-pressing the prayer it pressed last, so a slow
 * varbit update never makes it toggle a prayer straight back off.
 */
class PrayerFlicker @JvmOverloads constructor(
    val defaultPrayer: Prayer?,
    val threats: List<PrayerThreat>,
    var debug: Boolean = false,
) {
    private class PendingThreat(val threat: PrayerThreat, val addedTick: Long, var expiresTick: Long = NOT_EXPIRING)

    internal var currentTick: () -> Long = { ServerTick.count }

    private val pending = mutableListOf<PendingThreat>()
    private var lastPressed: Prayer? = null
    private var lastPressedTick = Long.MIN_VALUE / 2

    /** Every prayer the flicker may switch to. */
    val prayers: List<Prayer> = (listOfNotNull(defaultPrayer) + threats.map { it.prayer }).distinct()

    /** The prayers this flicker needs that are not on any action bar. */
    val missingPrayers: List<Prayer> get() = prayers.filterNot { isOnActionBar(actionBarName(it)) }

    /** The flicker's prayer that is currently active, if any. */
    val activePrayer: Prayer? get() = prayers.firstOrNull { it.active }

    /** The prayer the threats call for right now. */
    val requiredPrayer: Prayer?
        get() {
            val tick = currentTick()
            return pending.sortedByDescending { it.threat.priority }
                .firstOrNull { tick - it.addedTick >= it.threat.delayTicks }?.threat?.prayer
                ?: defaultPrayer
        }

    /** The names of the threats currently being tracked. */
    val trackedThreats: List<String> get() = pending.map { it.threat.name }

    /** Tracks threats and switches prayer when needed. Returns true when a prayer was pressed. */
    fun update(): Boolean {
        track()
        val prayer = requiredPrayer ?: return false
        return switchTo(prayer)
    }

    /** Turns off whichever of the flicker's prayers is active. */
    fun deactivate(): Boolean {
        val active = activePrayer ?: return false
        if (currentTick() - lastPressedTick < 1) return false
        return press(active).also { if (it) lastPressed = null }
    }

    private fun track() {
        val tick = currentTick()
        for (threat in threats) {
            if (pending.none { it.threat === threat } && threat.present()) {
                pending += PendingThreat(threat, tick)
                log("threat: ${threat.name}")
            }
        }
        pending.removeAll { entry ->
            if (entry.threat.present()) {
                entry.expiresTick = NOT_EXPIRING
                false
            } else if (entry.expiresTick == NOT_EXPIRING) {
                entry.expiresTick = tick + entry.threat.durationTicks
                false
            } else {
                tick > entry.expiresTick
            }
        }
    }

    private fun switchTo(prayer: Prayer): Boolean {
        val interval = if (prayer == lastPressed) SAME_PRAYER_INTERVAL else SWITCH_INTERVAL
        if (currentTick() - lastPressedTick <= interval) return false
        if (activePrayer == prayer) return false
        return press(prayer).also { if (it) lastPressed = prayer }
    }

    private fun press(prayer: Prayer): Boolean {
        val pressed = castAbility(actionBarName(prayer))
        if (pressed) lastPressedTick = currentTick()
        log("${if (pressed) "pressed" else "could not press"} ${actionBarName(prayer)}")
        return pressed
    }

    private fun log(message: String) {
        if (debug) println("[PrayerFlicker] $message")
    }

    private companion object {
        const val NOT_EXPIRING = -1L
        const val SWITCH_INTERVAL = 1
        const val SAME_PRAYER_INTERVAL = 4
    }
}

/** The name [prayer] carries on the action bar. */
fun actionBarName(prayer: Prayer): String = when (prayer) {
    Prayer.PROTECT_MAGIC -> "Protect from Magic"
    Prayer.PROTECT_RANGED -> "Protect from Missiles"
    Prayer.PROTECT_MELEE -> "Protect from Melee"
    Prayer.PROTECT_NECROMANCY -> "Protect from Necromancy"
    Prayer.DEFLECT_RANGE -> "Deflect Ranged"
    else -> prayer.name.split('_').joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
}
