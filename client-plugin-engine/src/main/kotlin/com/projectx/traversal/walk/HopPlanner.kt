package com.projectx.traversal.walk

import com.projectx.pathfinder.HopModel
import com.projectx.pathfinder.ServerWalk
import com.projectx.pathfinder.Steps
import com.projectx.pathfinder.WalkField
import com.projectx.pathfinder.WalkMap
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.sign

/** A hop worth casting, or [held] with the reason none is. */
internal class HopChoice private constructor(
    val landing: Tile?,
    val direction: Direction?,
    val aim: Tile?,
    val gain: Int,
    val held: String?,
) {
    val cast: Boolean get() = held == null

    override fun toString(): String = held ?: "lands $landing (gains $gain)"

    companion object {
        fun surge(landing: Tile, direction: Direction, gain: Int) = HopChoice(landing, direction, null, gain, null)

        fun dive(landing: Tile, aim: Tile, gain: Int) = HopChoice(landing, null, aim, gain, null)

        fun held(why: String) = HopChoice(null, null, null, 0, why)
    }
}

/**
 * Where along a route Surge and Dive are worth casting, judged on a [WalkMap]. A hop is judged by the walking steps
 * it saves toward a route tile well ahead, not by how close it lands to the route's own tiles: the server often walks
 * a parallel line of equal length a few tiles off them.
 */
internal class HopPlanner(private val map: WalkMap) {

    fun surgeLanding(from: Tile, direction: Direction?): Tile =
        if (direction == null) from else HopModel.surge(map.hopRule(), from, direction)

    fun diveLanding(from: Tile, aim: Tile): Tile? = HopModel.dive(map.hopRule(), from, aim)

    /** Walking steps a hop from [from] to [landing] saves toward [ahead]; [Int.MIN_VALUE] when the map cannot say. */
    fun progressGain(from: Tile, landing: Tile?, ahead: Tile): Int {
        if (landing == null || landing == from) return 0
        val field = map.field(ahead, PROGRESS_DEPTH) ?: return Int.MIN_VALUE
        var before = stepsOff(field, from)
        val after = field.distanceTo(landing)
        if (after < 0) return Int.MIN_VALUE
        if (before < 0) before = PROGRESS_DEPTH
        return before - after
    }

    /**
     * [field]'s steps to [tile], or one more than its steps to a neighbour a legal step leads off to when nothing walks
     * into [tile]: a player can stand where the search, run out from the goal, never enters.
     */
    fun stepsOff(field: WalkField, tile: Tile): Int {
        val d = field.distanceTo(tile)
        if (d != WalkMap.UNREACHABLE) return d
        var best = WalkMap.UNREACHABLE
        for (direction in Steps.SERVER_ORDER) {
            val dn = field.distanceTo(tile.x + direction.delta.x, tile.y + direction.delta.y, tile.plane)
            if (dn >= 0 && (best < 0 || dn + 1 < best) && map.canStep(tile.x, tile.y, tile.plane, direction)) best = dn + 1
        }
        return best
    }

    /** Walking steps from [landing] to the route's last tile; -1 when unknown. */
    fun endSteps(route: List<Tile>, landing: Tile?): Int {
        if (landing == null) return -1
        return map.field(route.last(), PROGRESS_DEPTH)?.distanceTo(landing) ?: -1
    }

    /**
     * Steps a surge to [landing] must gain: [SURGE_MIN_GAIN], or [SURGE_END_MIN_GAIN] near the route's end when the
     * landing is at most [SURGE_OVERSHOOT_MAX] steps past or beside its last tile, so a surge just short of the
     * objective is not held and walked instead.
     */
    fun surgeNeed(route: List<Tile>, at: Int, landing: Tile?): Int {
        if (at + SURGE_PROGRESS_AHEAD < route.size - 1) return SURGE_MIN_GAIN
        val back = endSteps(route, landing)
        return if (back in 0..SURGE_OVERSHOOT_MAX) SURGE_END_MIN_GAIN else SURGE_MIN_GAIN
    }

    /**
     * The tiles a player walking to [walkClick] may stand on 1..[ARM_STEPS] steps from [player]: a hop arms for a
     * server tick, and the player runs on while it does.
     */
    fun armingTiles(player: Tile, walkClick: Tile?): List<Tile> {
        if (walkClick == null || walkClick.plane != player.plane) return emptyList()
        val toClick = map.field(walkClick) ?: return emptyList()
        val left = toClick.distanceTo(player)
        if (left <= 0) return emptyList()
        val near = map.field(player, ARM_STEPS) ?: return emptyList()
        val steps = minOf(ARM_STEPS, left)
        val out = ArrayList<Tile>()
        for (dx in -steps..steps) for (dy in -steps..steps) {
            val x = player.x + dx
            val y = player.y + dy
            val k = near.distanceTo(x, y, player.plane)
            if (k in 1..steps && toClick.distanceTo(x, y, player.plane) == left - k) out += Tile.of(x, y, player.plane)
        }
        return out
    }

    /** Why a dive at [aim] goes wrong from one of the tiles it may fire from, or null when it lands well from all. */
    fun armedShort(arming: List<Tile>, aim: Tile, ahead: Tile): String? {
        for (from in arming) {
            val landing = diveLanding(from, aim) ?: return "it is refused from $from, where it fires"
            val gain = progressGain(from, landing, ahead)
            if (gain < DIVE_MIN_GAIN) return "from $from, where it fires, it lands $landing (gains $gain)"
        }
        return null
    }

    /**
     * A surge along the predicted server walk: cast only when every tile it may fire from goes the same way and each
     * landing gains enough, counted from where it fires.
     */
    fun surgeOnWalk(fires: List<ServerWalk.Fire>, route: List<Tile>, nearest: Int, ahead: Tile): HopChoice {
        val direction = fires.first().direction
        fires.firstOrNull { it.direction != direction }?.let {
            return HopChoice.held("the walk turns from $direction to ${it.direction} at ${it.tile}")
        }
        var least = Int.MAX_VALUE
        for (fire in fires) {
            val landing = surgeLanding(fire.tile, direction)
            val gain = progressGain(fire.tile, landing, ahead)
            val need = surgeNeed(route, nearest, landing)
            if (gain < need) return HopChoice.held("facing $direction from ${fire.tile} gains $gain of $need")
            least = minOf(least, gain)
        }
        return HopChoice.surge(surgeLanding(fires.first().tile, direction), direction, least)
    }

    /**
     * A surge the way of the player's last step, when no walk is predicted: cast when the route's next steps run the
     * same way (or it follows straight on from a dive) and the landing gains enough.
     */
    fun surgeByLastStep(direction: Direction?, chained: Boolean, route: List<Tile>, nearest: Int, player: Tile, ahead: Tile): HopChoice {
        if (direction == null) return HopChoice.held("no 8-way direction yet")
        val runs = routeAhead(route, nearest)
        if (runs != direction && !chained) {
            return HopChoice.held("facing $direction but the route goes ${runs ?: "round a bend"}")
        }
        val landing = surgeLanding(player, direction)
        val gain = progressGain(player, landing, ahead)
        val need = surgeNeed(route, nearest, landing)
        if (gain < need) return HopChoice.held("facing $direction gains $gain of $need")
        return HopChoice.surge(landing, direction, gain)
    }

    /**
     * The furthest route tile within [DIVE_AIM_MAX] whose dive saves at least [DIVE_MIN_GAIN] steps, from the player
     * and from every tile in [arming]. A dive aimed past its reach goes as far as it can that way, so the aim may lie
     * beyond ten tiles: the player keeps walking while it arms, and a tile exactly ten away would be reached short.
     */
    fun dive(route: List<Tile>, nearest: Int, player: Tile, arming: List<Tile>, ahead: Tile): HopChoice {
        val last = minOf(route.size - 1, nearest + DIVE_MAX_AHEAD)
        var aims = 0
        var noLanding = 0
        var armHeld = 0
        var bestGain = Int.MIN_VALUE
        for (i in last downTo nearest + 1) {
            val aim = route[i]
            if (aim.plane != player.plane || player.getLongestDelta(aim) > DIVE_AIM_MAX) continue
            aims++
            val landing = diveLanding(player, aim)
            if (landing == null) {
                noLanding++
                continue
            }
            val gain = progressGain(player, landing, ahead)
            bestGain = maxOf(bestGain, gain)
            if (gain < DIVE_MIN_GAIN) continue
            if (armedShort(arming, aim, ahead) != null) {
                armHeld++
                continue
            }
            return HopChoice.dive(landing, aim, gain)
        }
        if (aims == 0) return HopChoice.held("no route tile within $DIVE_AIM_MAX")
        return HopChoice.held(
            "$aims aim(s), $noLanding without a landing, " + (if (armHeld > 0) "$armHeld short from where it fires, " else "") +
                "best gain ${if (bestGain == Int.MIN_VALUE) "none" else bestGain} of $DIVE_MIN_GAIN",
        )
    }

    /**
     * Dive then Surge: a dive turns the player its own 8-way way, and with no walk click after it a surge cast on
     * landing follows that line. The surge's direction when it would gain enough from the landing, else null.
     */
    fun chainedSurge(route: List<Tile>, nearest: Int, player: Tile, landing: Tile?): Direction? {
        if (landing == null || landing == player) return null
        val direction = HopModel.dir8(landing.x - player.x, landing.y - player.y) ?: return null
        val at = routeIndexNear(route, nearest, landing)
        val ahead = route[minOf(route.size - 1, at + SURGE_PROGRESS_AHEAD)]
        val surgeLanding = surgeLanding(landing, direction)
        return if (progressGain(landing, surgeLanding, ahead) >= surgeNeed(route, at, surgeLanding)) direction else null
    }

    companion object {
        const val SURGE_MIN_GAIN = 8
        const val SURGE_OVERSHOOT_MAX = 2
        const val SURGE_END_MIN_GAIN = 4
        const val DIVE_MAX_AHEAD = 30
        const val DIVE_AIM_MAX = 20
        const val DIVE_MIN_GAIN = 6
        const val ARM_STEPS = 2
        const val SURGE_PROGRESS_AHEAD = 25
        const val PROGRESS_DEPTH = 80

        /** The way the route's next two steps both go, or null round a bend or at the end. */
        fun routeAhead(route: List<Tile>, nearest: Int): Direction? {
            val first = step(route, nearest + 1)
            return if (first != null && first == step(route, nearest + 2)) first else null
        }

        private fun step(route: List<Tile>, i: Int): Direction? {
            if (i < 1 || i >= route.size) return null
            val a = route[i - 1]
            val b = route[i]
            if (a.plane != b.plane) return null
            return Steps.of((b.x - a.x).sign, (b.y - a.y).sign)
        }

        /** True when a walk from [here] to [toward] keeps facing [direction]: [toward] lies on its line. */
        fun walkRunsAlong(direction: Direction, here: Tile, toward: Tile?): Boolean {
            if (toward == null || toward.plane != here.plane || toward == here) return true
            val dx = toward.x - here.x
            val dy = toward.y - here.y
            return dx.sign == direction.delta.x && dy.sign == direction.delta.y && (dx == 0 || dy == 0 || abs(dx) == abs(dy))
        }

        /** Index of the route tile nearest [tile] in the 40 after [from] (ties: the furthest); [from] when none. */
        fun routeIndexNear(route: List<Tile>, from: Int, tile: Tile): Int {
            var best = from
            var bestDistance = Int.MAX_VALUE
            for (i in minOf(route.size - 1, from + 40) downTo from + 1) {
                val r = route[i]
                if (r.plane != tile.plane) continue
                val d = r.getLongestDelta(tile)
                if (d < bestDistance) {
                    best = i
                    bestDistance = d
                }
            }
            return best
        }

        /** Index of the route tile nearest [player] in the 60 from [from] (ties: the first). */
        fun nearestFrom(route: List<Tile>, player: Tile, from: Int): Int {
            val start = from.coerceIn(0, route.size - 1)
            var best = start
            var bestDistance = Int.MAX_VALUE
            for (i in start until minOf(route.size, start + 60)) {
                val t = route[i]
                if (t.plane != player.plane) continue
                val d = t.getLongestDelta(player)
                if (d < bestDistance) {
                    best = i
                    bestDistance = d
                }
            }
            return best
        }
    }
}
