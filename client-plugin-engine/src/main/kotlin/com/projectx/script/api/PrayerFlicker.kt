package com.projectx.script.api

import java.util.function.BooleanSupplier
import java.util.function.Supplier

/**
 * Something that calls for a protection prayer: a projectile in flight, an NPC animation, or any condition. The
 * flicker switches to [prayer] [delayTicks] ticks after the threat appears and keeps it up until [durationTicks]
 * ticks after it is gone. When several threats are live, the highest [priority] wins.
 *
 * A [dynamic] threat picks its prayer each tick from [prayers]; [prayer] is then the first of them.
 */
class PrayerThreat private constructor(
    val name: String,
    val prayer: Prayer,
    val priority: Int,
    val delayTicks: Int,
    val durationTicks: Int,
    private val detect: () -> Prayer?,
    private val bypass: BooleanSupplier?,
    /** Every prayer this threat may call for. */
    val prayers: List<Prayer>,
) {
    // The companion's call through this shape compiles to a synthetic constructor that shipped in the API jar.
    private constructor(
        name: String,
        prayer: Prayer,
        priority: Int,
        delayTicks: Int,
        durationTicks: Int,
        detect: () -> Prayer?,
        bypass: BooleanSupplier?,
    ) : this(name, prayer, priority, delayTicks, durationTicks, detect, bypass, listOf(prayer))

    /** Outranks threats with a lower [priority] when both are live. */
    fun priority(priority: Int) = copy(priority = priority)

    /** Waits [ticks] server ticks after the threat appears before switching. */
    fun delayTicks(ticks: Int) = copy(delayTicks = ticks)

    /** Keeps the prayer up for [ticks] server ticks after the threat is gone. */
    fun durationTicks(ticks: Int) = copy(durationTicks = ticks)

    /** Ignores the threat whenever [bypass] holds. */
    fun unless(bypass: BooleanSupplier) = copy(bypass = bypass)

    /** The prayer this threat calls for right now, or null while it is not live. */
    fun currentPrayer(): Prayer? = detect()?.takeUnless { bypass?.asBoolean == true }

    internal fun present(): Boolean = currentPrayer() != null

    private fun copy(
        priority: Int = this.priority,
        delayTicks: Int = this.delayTicks,
        durationTicks: Int = this.durationTicks,
        bypass: BooleanSupplier? = this.bypass,
    ) = PrayerThreat(name, prayer, priority, delayTicks, durationTicks, detect, bypass, prayers)

    companion object {
        private const val DEFAULT_RANGE = 60

        private fun fixed(name: String, prayer: Prayer, live: () -> Boolean) =
            PrayerThreat(name, prayer, 0, 0, 0, { if (live()) prayer else null }, null)

        private fun projectileLive(ids: Collection<Int>, range: Int, lockedOnPlayer: Boolean): Boolean {
            val player = if (lockedOnPlayer) localPlayer else null
            return projectiles.any {
                it.id in ids &&
                    (player == null || it.lockedOnto(player)) &&
                    playerDistanceTo(it.tileX.toDouble(), it.tileY.toDouble()) <= range
            }
        }

        /** A projectile with id [projectileId] within [range] tiles of the player. */
        @JvmStatic
        @JvmOverloads
        fun projectile(name: String, prayer: Prayer, projectileId: Int, range: Int = DEFAULT_RANGE) =
            fixed(name, prayer) { projectileLive(listOf(projectileId), range, lockedOnPlayer = false) }

        /**
         * A projectile with id [projectileId] within [range] tiles of the player; with [lockedOnPlayer], only one
         * locked onto the player, so a projectile flying at someone else is not a threat.
         */
        @JvmStatic
        fun projectile(name: String, prayer: Prayer, projectileId: Int, range: Int = DEFAULT_RANGE, lockedOnPlayer: Boolean) =
            fixed(name, prayer) { projectileLive(listOf(projectileId), range, lockedOnPlayer) }

        /** A projectile with any of [projectileIds] within [range] tiles of the player, locked onto them if [lockedOnPlayer]. */
        @JvmStatic
        @JvmOverloads
        fun projectile(
            name: String,
            prayer: Prayer,
            projectileIds: Collection<Int>,
            range: Int = DEFAULT_RANGE,
            lockedOnPlayer: Boolean = false,
        ): PrayerThreat {
            val ids = projectileIds.toSet()
            return fixed(name, prayer) { projectileLive(ids, range, lockedOnPlayer) }
        }

        /** An NPC with id [npcId] within [range] tiles playing any of [animationIds]. */
        @JvmStatic
        fun animation(name: String, prayer: Prayer, npcId: Int, range: Int, vararg animationIds: Int) =
            fixed(name, prayer) {
                allNpcsWithinRange(range) { it.id == npcId && it.animationId in animationIds }.isNotEmpty()
            }

        /** An NPC with any of [npcIds] within [range] tiles playing any of [animationIds]. */
        @JvmStatic
        fun animation(name: String, prayer: Prayer, npcIds: Collection<Int>, range: Int, vararg animationIds: Int): PrayerThreat {
            val ids = npcIds.toSet()
            return fixed(name, prayer) {
                allNpcsWithinRange(range) { it.id in ids && it.animationId in animationIds }.isNotEmpty()
            }
        }

        /** Whatever [condition] says. */
        @JvmStatic
        fun condition(name: String, prayer: Prayer, condition: BooleanSupplier) =
            fixed(name, prayer) { condition.asBoolean }

        /**
         * A threat whose prayer is chosen each tick: [choose] returns the prayer to use, or null while there is no
         * threat. Only [prayers] are ever used, so the flicker can tell when one is already up; any other prayer
         * [choose] returns counts as no threat.
         */
        @JvmStatic
        fun dynamic(name: String, prayers: Collection<Prayer>, choose: Supplier<Prayer?>): PrayerThreat {
            val candidates = prayers.distinct()
            require(candidates.isNotEmpty()) { "PrayerThreat.dynamic(\"$name\") needs at least one prayer" }
            return PrayerThreat(name, candidates.first(), 0, 0, 0, { choose.get()?.takeIf { it in candidates } }, null, candidates)
        }
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
    private class PendingThreat(val threat: PrayerThreat, val addedTick: Long, var expiresTick: Long = NOT_EXPIRING) {
        var prayer: Prayer = threat.prayer
    }

    internal var currentTick: () -> Long = { ServerTick.count }

    private val pending = mutableListOf<PendingThreat>()
    private var lastPressed: Prayer? = null
    private var lastPressedTick = Long.MIN_VALUE / 2

    /** Every prayer the flicker may switch to. */
    val prayers: List<Prayer> = (listOfNotNull(defaultPrayer) + threats.flatMap { it.prayers }).distinct()

    /** The prayers this flicker needs that are not on any action bar. */
    val missingPrayers: List<Prayer> get() = prayers.filterNot { isOnActionBar(actionBarName(it)) }

    /** The flicker's prayer that is currently active, if any. */
    val activePrayer: Prayer? get() = prayers.firstOrNull { it.active }

    /** The prayer the threats call for right now. */
    val requiredPrayer: Prayer?
        get() {
            val tick = currentTick()
            return pending.sortedByDescending { it.threat.priority }
                .firstOrNull { tick - it.addedTick >= it.threat.delayTicks }?.prayer
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
        val live = threats.associateWith { it.currentPrayer() }
        for (threat in threats) {
            val prayer = live[threat] ?: continue
            if (pending.none { it.threat === threat }) {
                pending += PendingThreat(threat, tick).also { it.prayer = prayer }
                log("threat: ${threat.name}")
            }
        }
        pending.removeAll { entry ->
            val prayer = live[entry.threat]
            if (prayer != null) {
                entry.prayer = prayer
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

/**
 * The protection prayer against [style]: the Protect prayer on the normal prayer book, the Deflect curse when
 * [curses] (by default, whichever book the player is on).
 */
@JvmOverloads
fun protectionPrayerFor(style: CombatStyle, curses: Boolean = onCursesPrayers): Prayer = when (style) {
    CombatStyle.MELEE -> if (curses) Prayer.DEFLECT_MELEE else Prayer.PROTECT_MELEE
    CombatStyle.RANGED -> if (curses) Prayer.DEFLECT_RANGE else Prayer.PROTECT_RANGED
    CombatStyle.MAGIC -> if (curses) Prayer.DEFLECT_MAGIC else Prayer.PROTECT_MAGIC
    CombatStyle.NECROMANCY -> if (curses) Prayer.DEFLECT_NECROMANCY else Prayer.PROTECT_NECROMANCY
}
