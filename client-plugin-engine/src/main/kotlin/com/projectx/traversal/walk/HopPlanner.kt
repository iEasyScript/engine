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
 * What a hop's gain is counted toward: walking steps to [root] on [field] - the walk's last tile ([end]), or the
 * furthest route tile within [HopPlanner.ROOT_REACH] the player's own walk reaches - so a hop across a wall counts the
 * whole walk round it. With no root, the route tile [ahead].
 */
internal class Progress(val field: WalkField?, val root: Tile?, val rootIndex: Int, val end: Boolean, val ahead: Tile) {
    override fun toString(): String = if (field == null) "$ahead" else (if (end) "the walk's end " else "") + "$root"

    companion object {
        fun toward(ahead: Tile) = Progress(null, null, -1, false, ahead)
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

    /** The [Progress] for a player on [from] whose nearest route tile is [nearest]. */
    fun progress(route: List<Tile>, nearest: Int, from: Tile): Progress {
        val ahead = route[minOf(route.size - 1, nearest + SURGE_PROGRESS_AHEAD)]
        val here = map.field(from, ROOT_DEPTH) ?: return Progress.toward(ahead)
        for (i in minOf(route.size - 1, nearest + ROOT_REACH) downTo nearest + 1) {
            val tile = route[i]
            // The root's own search must still reach the player and the landings around them.
            if (tile.plane != from.plane || here.distanceTo(tile) !in 0..ROOT_DEPTH - ROOT_SLACK) continue
            val field = map.field(tile, ROOT_DEPTH) ?: continue
            return Progress(field, tile, i, i == route.size - 1, ahead)
        }
        return Progress.toward(ahead)
    }

    /** Walking steps a hop from [from] to [landing] saves toward [ahead]; [Int.MIN_VALUE] when the map cannot say. */
    fun progressGain(from: Tile, landing: Tile?, ahead: Tile): Int = gain(Progress.toward(ahead), from, landing)

    fun gain(progress: Progress, from: Tile, landing: Tile?): Int {
        if (landing == null || landing == from) return 0
        val field = progress.field ?: map.field(progress.ahead, PROGRESS_DEPTH) ?: return Int.MIN_VALUE
        var before = stepsOff(field, from)
        val after = field.distanceTo(landing)
        if (after < 0) return Int.MIN_VALUE
        if (before < 0) before = field.maxDepth
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
    fun surgeNeed(route: List<Tile>, at: Int, landing: Tile?): Int = endNeed(route, at, landing, SURGE_MIN_GAIN)

    /** The same end rule for a dive: a short dive onto the goal is worth it with nothing left to keep it for. */
    fun diveNeed(route: List<Tile>, at: Int, landing: Tile?): Int = endNeed(route, at, landing, DIVE_MIN_GAIN)

    private fun endNeed(route: List<Tile>, at: Int, landing: Tile?, need: Int): Int {
        if (at + SURGE_PROGRESS_AHEAD < route.size - 1) return need
        val back = endSteps(route, landing)
        return if (back in 0..SURGE_OVERSHOOT_MAX) SURGE_END_MIN_GAIN else need
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
    fun armedShort(arming: List<Tile>, aim: Tile, ahead: Tile): String? = armedShort(arming, aim, Progress.toward(ahead), DIVE_MIN_GAIN)

    fun armedShort(arming: List<Tile>, aim: Tile, progress: Progress, need: Int): String? {
        for (from in arming) {
            val landing = diveLanding(from, aim) ?: return "it is refused from $from, where it fires"
            val gain = gain(progress, from, landing)
            if (gain < need) return "from $from, where it fires, it lands $landing (gains $gain)"
        }
        return null
    }

    fun surgeOnWalk(fires: List<ServerWalk.Fire>, route: List<Tile>, nearest: Int, ahead: Tile): HopChoice =
        surgeOnWalk(fires, route, nearest, Progress.toward(ahead))

    /**
     * A surge along the predicted server walk: cast only when every tile it may fire from goes the same way and each
     * landing gains enough, counted from where it fires.
     */
    fun surgeOnWalk(fires: List<ServerWalk.Fire>, route: List<Tile>, nearest: Int, progress: Progress): HopChoice {
        val direction = fires.first().direction
        fires.firstOrNull { it.direction != direction }?.let {
            return HopChoice.held("the walk turns from $direction to ${it.direction} at ${it.tile}")
        }
        var least = Int.MAX_VALUE
        for (fire in fires) {
            val landing = surgeLanding(fire.tile, direction)
            val gain = gain(progress, fire.tile, landing)
            val need = surgeNeed(route, nearest, landing)
            if (gain < need) return HopChoice.held("facing $direction from ${fire.tile} gains $gain of $need")
            least = minOf(least, gain)
        }
        return HopChoice.surge(surgeLanding(fires.first().tile, direction), direction, least)
    }

    fun surgeByLastStep(direction: Direction?, chained: Boolean, route: List<Tile>, nearest: Int, player: Tile, ahead: Tile): HopChoice =
        surgeByLastStep(direction, chained, route, nearest, player, Progress.toward(ahead))

    /**
     * A surge the way of the player's last step, when no walk is predicted: cast when the route's next steps run the
     * same way (or it follows straight on from a dive) and the landing gains enough.
     */
    fun surgeByLastStep(direction: Direction?, chained: Boolean, route: List<Tile>, nearest: Int, player: Tile, progress: Progress): HopChoice {
        if (direction == null) return HopChoice.held("no 8-way direction yet")
        val runs = routeAhead(route, nearest)
        if (runs != direction && !chained) {
            return HopChoice.held("facing $direction but the route goes ${runs ?: "round a bend"}")
        }
        val landing = surgeLanding(player, direction)
        val gain = gain(progress, player, landing)
        val need = surgeNeed(route, nearest, landing)
        if (gain < need) return HopChoice.held("facing $direction gains $gain of $need")
        return HopChoice.surge(landing, direction, gain)
    }

    fun dive(route: List<Tile>, nearest: Int, player: Tile, arming: List<Tile>, ahead: Tile): HopChoice =
        dive(route, nearest, player, arming, Progress.toward(ahead))

    /**
     * Of the route tiles up to [DIVE_SHORTCUT_AHEAD] on and within [DIVE_AIM_MAX], the aim whose dive gains the most
     * (ties: the furthest), at least [diveNeed], landing well from every tile in [arming]. A dive aimed past its reach
     * goes as far as it can that way, so the aim may lie beyond ten tiles: the player keeps walking while it arms.
     */
    fun dive(route: List<Tile>, nearest: Int, player: Tile, arming: List<Tile>, progress: Progress): HopChoice {
        val last = minOf(route.size - 1, nearest + DIVE_SHORTCUT_AHEAD)
        var aims = 0
        var noLanding = 0
        var armHeld = 0
        var bestGain = Int.MIN_VALUE
        var pick: HopChoice? = null
        for (i in last downTo nearest + 1) {
            val aim = route[i]
            if (aim.plane != player.plane || player.getLongestDelta(aim) > DIVE_AIM_MAX) continue
            aims++
            val landing = diveLanding(player, aim)
            if (landing == null) {
                noLanding++
                continue
            }
            val gain = gain(progress, player, landing)
            bestGain = maxOf(bestGain, gain)
            val need = diveNeed(route, nearest, landing)
            if (gain < need || gain <= (pick?.gain ?: Int.MIN_VALUE)) continue
            // On the last stretch the player runs on toward the goal while the dive arms, so from there it may gain less.
            if (armedShort(arming, aim, progress, if (need < DIVE_MIN_GAIN) 1 else need) != null) {
                armHeld++
                continue
            }
            pick = HopChoice.dive(landing, aim, gain)
        }
        if (pick != null) return pick
        if (aims == 0) return HopChoice.held("no route tile within $DIVE_AIM_MAX")
        return HopChoice.held(
            "$aims aim(s), $noLanding without a landing, " + (if (armHeld > 0) "$armHeld short from where it fires, " else "") +
                "best gain ${if (bestGain == Int.MIN_VALUE) "none" else bestGain} of $DIVE_MIN_GAIN",
        )
    }

    fun chainedSurge(route: List<Tile>, nearest: Int, player: Tile, landing: Tile?): Direction? {
        val at = if (landing == null) nearest else routeIndexNear(route, nearest, landing)
        return chainedSurge(route, nearest, player, landing, Progress.toward(route[minOf(route.size - 1, at + SURGE_PROGRESS_AHEAD)]))
    }

    /**
     * Dive then Surge: a dive turns the player the nearest of the 8 ways to its line, and with no walk click after it
     * a surge cast on landing goes that way. The surge's direction when it would gain enough from the landing, else null.
     */
    fun chainedSurge(route: List<Tile>, nearest: Int, player: Tile, landing: Tile?, progress: Progress): Direction? {
        if (landing == null || landing == player) return null
        val direction = HopModel.facing(landing.x - player.x, landing.y - player.y) ?: return null
        val at = routeIndexNear(route, nearest, landing)
        val surgeLanding = surgeLanding(landing, direction)
        return if (gain(progress, landing, surgeLanding) >= surgeNeed(route, at, surgeLanding)) direction else null
    }

    companion object {
        const val SURGE_MIN_GAIN = 8
        const val SURGE_OVERSHOOT_MAX = 2
        const val SURGE_END_MIN_GAIN = 4
        const val DIVE_MAX_AHEAD = 30
        const val DIVE_SHORTCUT_AHEAD = 120
        const val DIVE_AIM_MAX = 20
        const val DIVE_MIN_GAIN = 6
        const val ARM_STEPS = 2
        const val SURGE_PROGRESS_AHEAD = 25
        const val PROGRESS_DEPTH = 80
        const val ROOT_REACH = 160
        const val ROOT_DEPTH = WalkMap.WINDOW_RADIUS
        private const val ROOT_SLACK = 12

        /** No hop is looked for with fewer route tiles than this left: one gaining [SURGE_END_MIN_GAIN] needs one more. */
        const val END_GATE_TILES = SURGE_END_MIN_GAIN + 1

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

        /** Index of the route tile nearest [player] in the 60 from [from] ([nearestIndex]); [from] when none. */
        fun nearestFrom(route: List<Tile>, player: Tile, from: Int): Int {
            val start = from.coerceIn(0, route.size - 1)
            val i = nearestIndex(route, player, start, start + 60)
            return if (i < 0) start else i
        }

        /**
         * Index of the route tile on [player]'s plane nearest it in [from, to), -1 when none. Ties go to the first,
         * except along one run of equally near tiles, where the straight-line nearest wins: a player walking beside a
         * straight stretch is equally near all of it by Chebyshev distance. A later, separate run still loses.
         */
        fun nearestIndex(route: List<Tile>, player: Tile, from: Int, to: Int): Int {
            var nearest = -1
            var best = Int.MAX_VALUE
            var bestSquared = Int.MAX_VALUE
            var run = false
            for (i in from.coerceIn(0, maxOf(0, route.size - 1)) until minOf(to, route.size)) {
                val t = route[i]
                if (t.plane != player.plane) {
                    run = false
                    continue
                }
                val d = player.getLongestDelta(t)
                val dx = t.x - player.x
                val dy = t.y - player.y
                val squared = dx * dx + dy * dy
                if (d < best || run && d == best && squared < bestSquared) {
                    best = d
                    bestSquared = squared
                    nearest = i
                    run = true
                } else if (d != best) {
                    run = false
                }
            }
            return nearest
        }
    }
}
