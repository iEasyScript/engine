package com.projectx.traversal.walk

import com.projectx.pathfinder.HopModel
import com.projectx.pathfinder.WalkField
import com.projectx.pathfinder.WalkMap
import world.gregs.voidps.type.Tile
import java.util.Locale

/**
 * When a Powerburst of acceleration pays for itself on a walk. Surge and Dive cooldowns drop to 1.2 s for six seconds
 * and the dose is spent, so it is only worth it on a stretch where several hops in a row really land along the route.
 * The burst is simulated: hops alternate Dive and Surge from where the player stands, each landing as [HopPlanner]
 * predicts, until one no longer gains what the walker asks of a real hop. The saving leaves out the hops that would
 * happen anyway and the running each hop's time costs.
 */
internal class AccelerationPlanner(private val map: WalkMap, private val hops: HopPlanner = HopPlanner(map)) {

    data class Estimate(val hops: Int, val steps: Int, val savingSeconds: Double)

    /** The better of the two openings, Dive first and Surge first along [bearing] (skipped when it is negative). */
    fun simulate(route: List<Tile>, index: Int, start: Tile, bearing: Double, surge: Boolean, dive: Boolean, anyway: Int): Estimate {
        var best = Estimate(0, 0, 0.0)
        if (dive) best = opening(route, index, start, bearing, surge, dive = true, diveFirst = true, anyway = anyway)
        if (surge && bearing >= 0) {
            val s = opening(route, index, start, bearing, surge = true, dive = dive, diveFirst = false, anyway = anyway)
            if (s.savingSeconds > best.savingSeconds || s.savingSeconds == best.savingSeconds && s.steps > best.steps) best = s
        }
        return best
    }

    fun opening(
        route: List<Tile>,
        index: Int,
        start: Tile,
        initialBearing: Double,
        surge: Boolean,
        dive: Boolean,
        diveFirst: Boolean,
        anyway: Int,
    ): Estimate {
        var hopCount = 0
        var saved = 0
        var net = 0.0
        val field = rootField(route, index, start.plane)
        var left = field?.distanceTo(start) ?: -1
        var position = start
        var at = index
        var bearing = initialBearing
        var diveTurn = diveFirst
        var n = 0
        while (field != null && left >= 0 && n < MAX_HOPS) {
            n++
            var landing: Tile? = null
            var gain = 0
            var nextBearing = bearing
            if (diveTurn) {
                for (i in minOf(route.size - 1, at + HopPlanner.DIVE_MAX_AHEAD) downTo at + 1) {
                    val aim = route[i]
                    if (aim.plane != position.plane || position.getLongestDelta(aim) > HopPlanner.DIVE_AIM_MAX) continue
                    val l = hops.diveLanding(position, aim)
                    val g = gain(field, left, l)
                    if (l == null || g < HopPlanner.DIVE_MIN_GAIN) continue
                    landing = l
                    gain = g
                    nextBearing = HopModel.dir8(l.x - position.x, l.y - position.y)?.let(HopModel::bearing) ?: bearing
                    break
                }
            } else if (bearing >= 0) {
                val l = hops.surgeLanding(position, HopModel.fromBearing(bearing))
                val g = gain(field, left, l)
                if (g >= HopPlanner.SURGE_MIN_GAIN) {
                    landing = l
                    gain = g
                }
            }
            if (landing == null) break
            saved += gain
            if (hopCount >= anyway) net += gain - HOP_SECONDS * TILES_PER_SECOND
            left -= gain
            at = HopPlanner.routeIndexNear(route, at, landing)
            position = landing
            bearing = nextBearing
            hopCount++
            if (surge && dive) diveTurn = !diveTurn
        }
        return Estimate(hopCount, saved, maxOf(0.0, net) / TILES_PER_SECOND)
    }

    /**
     * The estimate from where the player stands, or null with [why] when the dose should wait: it saves too little, or
     * a point a little further along the route would save clearly more.
     */
    fun evaluate(route: List<Tile>, nearest: Int, player: Tile, bearing: Double, surge: Boolean, dive: Boolean, anyway: Int, why: StringBuilder): Estimate? {
        val here = simulate(route, nearest, player, bearing, surge, dive, anyway)
        if (here.savingSeconds < MIN_SAVING_SECONDS) {
            why.append("saves ").append(format(here.savingSeconds)).append(" s here, need ").append(format(MIN_SAVING_SECONDS))
            return null
        }
        for (ahead in LOOK_AHEAD) {
            val i = nearest + ahead
            if (i + 1 >= route.size) break
            val tile = route[i]
            if (!map.trusted(tile)) break
            val toward = route[minOf(route.size - 1, i + 5)]
            val there = simulate(route, i, tile, HopModel.bearing(tile, toward), surge, dive, anyway)
            if (there.savingSeconds > here.savingSeconds * BETTER_AHEAD) {
                why.append("saves ").append(format(here.savingSeconds)).append(" s here but ")
                    .append(format(there.savingSeconds)).append(" s ").append(ahead).append(" tiles ahead")
                return null
            }
        }
        return here
    }

    private fun rootField(route: List<Tile>, index: Int, plane: Int): WalkField? {
        for (i in minOf(route.size - 1, index + ROOT_AHEAD) downTo index + 1) {
            val tile = route[i]
            if (tile.plane != plane) continue
            map.field(tile, FIELD_DEPTH)?.let { return it }
        }
        return null
    }

    private fun gain(field: WalkField, left: Int, landing: Tile?): Int {
        if (landing == null) return Int.MIN_VALUE
        val d = field.distanceTo(landing)
        return if (d < 0) Int.MIN_VALUE else left - d
    }

    private fun format(value: Double) = String.format(Locale.ROOT, "%.1f", value)

    companion object {
        const val BURST_MS = 6_000L
        const val HOP_SECONDS = 0.85
        const val MAX_HOPS = 7
        const val ROOT_AHEAD = 90
        const val FIELD_DEPTH = 96
        const val TILES_PER_SECOND = 2.0 / 0.6
        const val MIN_SAVING_SECONDS = 5.0
        val LOOK_AHEAD = intArrayOf(10, 20, 30, 45)
        const val BETTER_AHEAD = 1.25
    }
}
