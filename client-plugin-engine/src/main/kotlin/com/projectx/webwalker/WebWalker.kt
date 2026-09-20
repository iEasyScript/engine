package com.projectx.webwalker

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.LODESTONE_MAP_INTERFACE
import com.projectx.script.api.Lodestone
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.interactComponent
import com.projectx.script.api.isLodestoneUiOpen
import com.projectx.script.api.localPlayer
import com.projectx.script.api.openLodestoneMap
import com.projectx.script.api.walkTo
import com.projectx.util.random
import world.gregs.voidps.type.Tile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Walks to any tile on the world map, planning the route from cache collision.
 *
 * Routes cross doors, opening them when they are shut, and take [WebLink]s - staircases, ladders, shortcuts and
 * curated doors - so a destination on another floor is reachable. A long walk may still start with a teleport to
 * an unlocked lodestone when that gets there sooner.
 *
 * A link the account cannot use is left out of the search; see [WebLinkPermissions]. Teleports other than
 * lodestones are not part of a route yet, so a destination that needs one reports [WebWalkStatus.NO_PATH].
 *
 * Planning runs on a background thread: script bodies run on the game thread, and a long search there would
 * freeze the client.
 */
object WebWalker {
    const val DEFAULT_ARRIVE_DISTANCE = 2

    private const val INSTANCE_MIN_X = 6400
    private const val SEARCH_TIMEOUT_MS = 60_000L
    private const val SEARCH_POLL_MS = 50
    private const val OFF_PATH_DISTANCE = 8
    private const val MAX_PLANS = 8
    private const val MAX_STALLS = 3
    private const val MAX_DOOR_ATTEMPTS = 3
    private const val MAX_LINK_ATTEMPTS = 3

    // A staircase or shortcut runs an animation and may load a new area, so it is given longer than a door.
    private const val LINK_TIMEOUT_MS = 12_000L

    /** How long a link's "where to?" is given to appear before the answer is attempted anyway. */
    private const val CHOICE_TIMEOUT_MS = 4_000L
    private const val DOOR_SEARCH_RANGE = 6
    private const val ARRIVAL_SLACK = 3
    private const val STEP_TIMEOUT_MS = 700L
    private const val STALL_GRACE_MS = 1200L

    // A lodestone teleport takes about ten seconds, the time of roughly thirty tiles walked; closer than
    // MIN_TELEPORT_DISTANCE it never pays.
    private const val TELEPORT_COST_TILES = 30
    private const val MIN_TELEPORT_DISTANCE = 40
    private const val LODESTONE_CANDIDATES = 3
    private const val ARRIVAL_RADIUS = 10
    private const val LODESTONE_MAP_TIMEOUT_MS = 5000L
    private const val TELEPORT_TIMEOUT_MS = 25_000L

    private val DOOR_OPTIONS = listOf("Open", "Go-through", "Pass-through")

    private val planner = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "projectx-webwalker").apply { isDaemon = true }
    }

    /** Plans a route off the game thread. */
    @JvmStatic
    @JvmOverloads
    fun findPathAsync(
        startX: Int,
        startY: Int,
        destX: Int,
        destY: Int,
        plane: Int,
        arriveDistance: Int = 0,
    ): CompletableFuture<WebWalkResult> = CompletableFuture.supplyAsync(
        { plan(startX, startY, destX, destY, plane, plane, arriveDistance, WebLinkPermissions.UNRESTRICTED) },
        planner,
    )

    /**
     * Plans a route and blocks until it is ready. Never call it from a script body, which runs on the game thread;
     * use [findPathAsync], or the suspending [findPath] from Kotlin.
     */
    @JvmStatic
    @JvmOverloads
    fun findPath(startX: Int, startY: Int, destX: Int, destY: Int, plane: Int, arriveDistance: Int = 0): WebWalkResult =
        findPathAsync(startX, startY, destX, destY, plane, arriveDistance).join()

    /** Plans a route from [from] to [destination], suspending the script rather than blocking the game thread. */
    suspend fun findPath(script: Script, from: Tile, destination: Tile, arriveDistance: Int = 0): WebWalkResult {
        // Requirements read varbits, levels and the money pouch, which may only be touched here on the game
        // thread; the planner runs on its own thread and consults the snapshot instead.
        val permissions = runCatching { WebLinkPermissions.snapshot() }.getOrDefault(WebLinkPermissions.UNRESTRICTED)
        val future = CompletableFuture.supplyAsync(
            { plan(from.x, from.y, destination.x, destination.y, from.plane, destination.plane, arriveDistance, permissions) },
            planner,
        )
        script.delayUntil(SEARCH_TIMEOUT_MS, SEARCH_POLL_MS) { future.isDone || script.stopped }
        if (!future.isDone) {
            future.cancel(false)
            return WebWalkResult(WebWalkStatus.STOPPED, "Route planning did not finish")
        }
        return future.join()
    }

    /**
     * Walks the player to within [arriveDistance] tiles of [destination], counting diagonals as one. With
     * [useLodestones], a long walk first teleports to an unlocked lodestone when that gets there sooner.
     */
    suspend fun walk(
        script: Script,
        destination: Tile,
        arriveDistance: Int = DEFAULT_ARRIVE_DISTANCE,
        useLodestones: Boolean = true,
    ): WebWalkResult {
        val arrive = arriveDistance.coerceAtLeast(0)
        var path: WebPath? = null
        var lodestone: Lodestone? = null
        var plans = 0
        var stalls = 0
        var doorAttempts = 0
        var linkAttempts = 0

        if (useLodestones && !arrived(destination, arrive)) {
            val choice = chooseLodestone(script, destination, arrive)
            path = choice.walkPath
            if (choice.lodestone != null && teleport(script, choice.lodestone)) {
                lodestone = choice.lodestone
                path = null
            }
        }

        while (!script.stopped) {
            val me = localPlayer.tile
            if (arrived(destination, arrive)) {
                return WebWalkResult(WebWalkStatus.ARRIVED, "Arrived at ${destination.x},${destination.y}", path, lodestone)
            }

            if (path == null || path.distance(path.nearestIndex(me.x, me.y, me.plane), me.x, me.y) > OFF_PATH_DISTANCE || stalls >= MAX_STALLS) {
                if (++plans > MAX_PLANS) return WebWalkResult(WebWalkStatus.STUCK, "No progress after $MAX_PLANS routes", path, lodestone)
                val planned = findPath(script, me, destination, arrive)
                if (planned.status != WebWalkStatus.PATH_FOUND) return planned.via(lodestone)
                path = planned.path!!
                stalls = 0
            }
            val route = path

            val here = route.nearestIndex(me.x, me.y, me.plane)
            val door = route.nextDoor(here + 1)
            val link = route.nextLink(here + 1)

            // Both are things the route walks up to and then performs, so the walk stops at whichever comes first.
            val barrier = when {
                door == -1 -> link
                link == -1 -> door
                else -> min(door, link)
            }

            if (link != -1 && link == barrier && route.distance(link - 1, me.x, me.y) <= 1) {
                if (++linkAttempts > MAX_LINK_ATTEMPTS) {
                    val step = route.linkAt(link)
                    return WebWalkResult(WebWalkStatus.STUCK, "Could not take $step", route, lodestone)
                }
                if (passLink(script, route, link)) linkAttempts = 0
                continue
            }

            if (door != -1 && door == barrier && route.distance(door - 1, me.x, me.y) <= 1) {
                if (++doorAttempts > MAX_DOOR_ATTEMPTS) {
                    return WebWalkResult(WebWalkStatus.STUCK, "Could not get through the door at ${route.getX(door)},${route.getY(door)}", route, lodestone)
                }
                if (passDoor(script, route, door)) doorAttempts = 0
                continue
            }

            val lookahead = random(PlayerProfiles.get().futurePathStepMin, PlayerProfiles.get().futurePathStepMax + 1)
            var target = (here + lookahead).coerceAtMost(route.lastIndex)
            if (barrier != -1) target = target.coerceAtMost(barrier - 1)
            if (target <= here) target = if (barrier != -1) barrier - 1 else (here + 1).coerceAtMost(route.lastIndex)

            // A stop tile (before a door or a link, or the end) must be reached closely: with the usual slack the
            // wait would already be satisfied and the loop would click again every pass.
            val slack = when {
                barrier != -1 && target == barrier - 1 -> 1
                target == route.lastIndex -> arrive.coerceAtMost(ARRIVAL_SLACK)
                else -> ARRIVAL_SLACK
            }
            val before = me
            if (!step(script, route.tile(target), slack)) {
                stalls = MAX_STALLS
                continue
            }
            stalls = if (localPlayer.tile == before) stalls + 1 else 0
        }
        return WebWalkResult(WebWalkStatus.STOPPED, "The script stopped while walking", path, lodestone)
    }

    private class LodestoneChoice(val lodestone: Lodestone?, val walkPath: WebPath?)

    /**
     * Picks the unlocked lodestone whose teleport plus walk beats walking the whole way, or none. Walking is only
     * planned when its straight-line distance could still beat the best lodestone, so a far destination never pays
     * for a walking search that cannot win; a walking route that was planned is handed back for reuse.
     */
    private suspend fun chooseLodestone(script: Script, destination: Tile, arrive: Int): LodestoneChoice {
        val me = localPlayer.tile
        val onPlane = me.plane == destination.plane && me.x < INSTANCE_MIN_X
        val direct = if (onPlane) chebyshev(me.x, me.y, destination.x, destination.y) else Int.MAX_VALUE
        if (direct <= MIN_TELEPORT_DISTANCE) return LodestoneChoice(null, null)

        val candidates = Lodestone.entries
            .filter { it.tile.plane == destination.plane && runCatching { it.isUnlocked() }.getOrDefault(false) }
            .map { it to chebyshev(it.tile.x, it.tile.y, destination.x, destination.y) }
            .filter { (_, distance) -> distance + TELEPORT_COST_TILES < direct }
            .sortedBy { it.second }
            .take(LODESTONE_CANDIDATES)

        var best: Lodestone? = null
        var bestCost = Int.MAX_VALUE
        for ((candidate, _) in candidates) {
            if (script.stopped) break
            val route = findPath(script, candidate.tile, destination, arrive)
            val cost = (route.path?.size ?: continue) + TELEPORT_COST_TILES
            if (route.status == WebWalkStatus.PATH_FOUND && cost < bestCost) {
                best = candidate
                bestCost = cost
            }
        }
        if (best == null || !onPlane || direct >= bestCost) return LodestoneChoice(best, null)

        val walking = findPath(script, me, destination, arrive)
        val walkPath = walking.path?.takeIf { walking.status == WebWalkStatus.PATH_FOUND }
        return if (walkPath != null && walkPath.size <= bestCost) LodestoneChoice(null, walkPath) else LodestoneChoice(best, walkPath)
    }

    /** Opens the lodestone map if needed and teleports to [lodestone]; true once the player has arrived there. */
    private suspend fun teleport(script: Script, lodestone: Lodestone): Boolean {
        println("[WebWalk] Teleporting to the ${lodestone.name} lodestone")
        if (!isLodestoneUiOpen) {
            if (!openLodestoneMap()) {
                println("[WebWalk] No home teleport button on the minimap; walking instead")
                return false
            }
            script.delayUntil(LODESTONE_MAP_TIMEOUT_MS) { isLodestoneUiOpen }
            if (!isLodestoneUiOpen) {
                println("[WebWalk] The lodestone map did not open; walking instead")
                return false
            }
            script.delay(random(250, 600))
        }
        if (!interactComponent(1, LODESTONE_MAP_INTERFACE, lodestone.id)) {
            println("[WebWalk] The ${lodestone.name} button is not on the lodestone map; walking instead")
            return false
        }
        script.delayUntil(TELEPORT_TIMEOUT_MS) { localPlayer.tile.withinDistance(lodestone.tile, ARRIVAL_RADIUS) }
        if (!localPlayer.tile.withinDistance(lodestone.tile, ARRIVAL_RADIUS)) {
            println("[WebWalk] The ${lodestone.name} teleport did not arrive; walking instead")
            return false
        }
        script.delayUntil(STALL_GRACE_MS * 4) { !localPlayer.isAnimating }
        return true
    }

    private fun arrived(destination: Tile, arrive: Int): Boolean {
        val me = localPlayer.tile
        return me.plane == destination.plane && chebyshev(me.x, me.y, destination.x, destination.y) <= arrive
    }

    private fun plan(
        startX: Int,
        startY: Int,
        destX: Int,
        destY: Int,
        plane: Int,
        destPlane: Int,
        arriveDistance: Int,
        permissions: WebLinkPermissions,
    ): WebWalkResult {
        if (startX >= INSTANCE_MIN_X || destX >= INSTANCE_MIN_X) {
            return WebWalkResult(WebWalkStatus.NOT_IN_WORLD, "Web walking does not work inside instances")
        }
        return try {
            WebPathfinder().find(startX, startY, plane, destX, destY, destPlane, arriveDistance, permissions)
        } catch (t: Throwable) {
            WebWalkResult(WebWalkStatus.NO_PATH, "Route planning failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Clicks towards [target] and waits until the player is close to it or has stopped short. */
    private suspend fun step(script: Script, target: Tile, slack: Int): Boolean {
        // Already there: clicking would return at once and repeat every pass, so wait a step's time instead and let
        // the caller count it as a stall.
        if (chebyshev(localPlayer.tile.x, localPlayer.tile.y, target.x, target.y) <= slack) {
            script.delay(STEP_TIMEOUT_MS.toInt())
            return true
        }
        val minimap = random(100) < PlayerProfiles.get().minimapWalkPerc
        if (!walkTo(target, minimap)) return false
        val clickedAt = System.currentTimeMillis()
        val timeout = STALL_GRACE_MS + STEP_TIMEOUT_MS * chebyshev(localPlayer.tile.x, localPlayer.tile.y, target.x, target.y)
        script.delayUntil(timeout) {
            val tile = localPlayer.tile
            chebyshev(tile.x, tile.y, target.x, target.y) <= slack ||
                (System.currentTimeMillis() - clickedAt > STALL_GRACE_MS && !localPlayer.isMoving)
        }
        return true
    }

    /**
     * Walks onto the tile the link starts from, clicks its object, and waits to arrive on the other side.
     *
     * Arrival is judged against the link's whole destination area rather than the one tile the route aimed at: a
     * staircase drops the player anywhere in the room at the top, and the route only picked a representative tile.
     */
    private suspend fun passLink(script: Script, path: WebPath, index: Int): Boolean {
        val link = path.linkAt(index) ?: return false
        val approach = path.tile(index - 1)
        val me = localPlayer.tile
        if (me != approach && me.plane == approach.plane && !localPlayer.isMoving) {
            walkTo(approach, false)
            script.delayUntil(STALL_GRACE_MS + STEP_TIMEOUT_MS * 3) { localPlayer.tile == approach }
        }

        val target = findClosestObject(link.searchRadius) { obj ->
            (obj.id == link.objectId || obj.visibleTypeId == link.objectId) && obj.hasOption(link.action)
        } ?: findClosestObject(link.searchRadius) { it.hasOption(link.action) }

        if (target == null) {
            println("[WebWalk] No '${link.action}' object ${link.objectId} in range for $link")
            return false
        }
        if (!target.interact(link.action)) return false

        // Some ways through ask where to go and leave the player standing outside until that is answered.
        val choice = link.choice
        if (choice != null) {
            script.delayUntil(CHOICE_TIMEOUT_MS) { isDialogOpen() }
            if (!continueDialogueContaining(choice)) {
                println("[WebWalk] $link offered no \"$choice\" to pick")
                return false
            }
        }

        script.delayUntil(LINK_TIMEOUT_MS) {
            val tile = localPlayer.tile
            link.to.contains(tile.x, tile.y, tile.plane)
        }
        val arrived = localPlayer.tile.let { link.to.contains(it.x, it.y, it.plane) }
        if (arrived) script.delayUntil(STALL_GRACE_MS) { !localPlayer.isMoving }
        return arrived
    }

    /** Opens the door crossed on the way to step [door] if it is shut, then steps through. True once past it. */
    private suspend fun passDoor(script: Script, path: WebPath, door: Int): Boolean {
        val near = path.tile(door - 1)
        val far = path.tile(door)
        if (localPlayer.tile != near && !localPlayer.isMoving) {
            walkTo(near, false)
            script.delayUntil(STALL_GRACE_MS + STEP_TIMEOUT_MS * 3) { localPlayer.tile == near }
        }

        val closed = closedDoor(near, far)
        if (closed != null) {
            val option = DOOR_OPTIONS.first { closed.hasOption(it) }
            closed.interact(option)
            script.delayUntil(STALL_GRACE_MS * 3) { closedDoor(near, far) == null || localPlayer.tile == far }
        }
        if (localPlayer.tile != far) {
            walkTo(far, false)
            script.delayUntil(STALL_GRACE_MS + STEP_TIMEOUT_MS * 2) { localPlayer.tile == far }
        }
        return localPlayer.tile == far
    }

    private fun closedDoor(near: Tile, far: Tile): SceneObject? = findClosestObject(DOOR_SEARCH_RANGE) { obj ->
        (obj.tile == near || obj.tile == far) && obj.shape.slot == 0 && DOOR_OPTIONS.any { obj.hasOption(it) }
    }

    private fun chebyshev(x: Int, y: Int, otherX: Int, otherY: Int) = max(abs(x - otherX), abs(y - otherY))
}
