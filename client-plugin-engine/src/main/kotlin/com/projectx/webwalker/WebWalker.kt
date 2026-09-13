package com.projectx.webwalker

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.util.random
import world.gregs.voidps.type.Tile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Walks to any tile on the world map, planning the route from cache collision.
 *
 * Routes stay on one plane and cross doors, opening them when they are shut; stairs, ladders, shortcuts and
 * teleports are not part of a route yet, so a destination that needs one reports [WebWalkStatus.NO_PATH].
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
    private const val DOOR_SEARCH_RANGE = 6
    private const val ARRIVAL_SLACK = 3
    private const val STEP_TIMEOUT_MS = 700L
    private const val STALL_GRACE_MS = 1200L

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
    ): CompletableFuture<WebWalkResult> = CompletableFuture.supplyAsync({ plan(startX, startY, destX, destY, plane, plane, arriveDistance) }, planner)

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
        if (from.plane != destination.plane) {
            return WebWalkResult(WebWalkStatus.OTHER_FLOOR, "The destination is on plane ${destination.plane}, the start on ${from.plane}")
        }
        val future = CompletableFuture.supplyAsync(
            { plan(from.x, from.y, destination.x, destination.y, from.plane, destination.plane, arriveDistance) },
            planner,
        )
        script.delayUntil(SEARCH_TIMEOUT_MS, SEARCH_POLL_MS) { future.isDone || script.stopped }
        if (!future.isDone) {
            future.cancel(false)
            return WebWalkResult(WebWalkStatus.STOPPED, "Route planning did not finish")
        }
        return future.join()
    }

    /** Walks the player to within [arriveDistance] tiles of [destination], counting diagonals as one. */
    suspend fun walk(script: Script, destination: Tile, arriveDistance: Int = DEFAULT_ARRIVE_DISTANCE): WebWalkResult {
        val arrive = arriveDistance.coerceAtLeast(0)
        var path: WebPath? = null
        var plans = 0
        var stalls = 0
        var doorAttempts = 0

        while (!script.stopped) {
            val me = localPlayer.tile
            if (me.plane == destination.plane && chebyshev(me.x, me.y, destination.x, destination.y) <= arrive) {
                return WebWalkResult(WebWalkStatus.ARRIVED, "Arrived at ${destination.x},${destination.y}", path)
            }

            if (path == null || path.distance(path.nearestIndex(me.x, me.y), me.x, me.y) > OFF_PATH_DISTANCE || stalls >= MAX_STALLS) {
                if (++plans > MAX_PLANS) return WebWalkResult(WebWalkStatus.STUCK, "No progress after $MAX_PLANS routes", path)
                val planned = findPath(script, me, destination, arrive)
                if (planned.status != WebWalkStatus.PATH_FOUND) return planned
                path = planned.path!!
                stalls = 0
            }
            val route = path

            val here = route.nearestIndex(me.x, me.y)
            val door = route.nextDoor(here + 1)
            if (door != -1 && route.distance(door - 1, me.x, me.y) <= 1) {
                if (++doorAttempts > MAX_DOOR_ATTEMPTS) {
                    return WebWalkResult(WebWalkStatus.STUCK, "Could not get through the door at ${route.getX(door)},${route.getY(door)}", route)
                }
                if (passDoor(script, route, door)) doorAttempts = 0
                continue
            }

            val lookahead = random(PlayerProfiles.get().futurePathStepMin, PlayerProfiles.get().futurePathStepMax + 1)
            var target = (here + lookahead).coerceAtMost(route.lastIndex)
            if (door != -1) target = target.coerceAtMost(door - 1)
            if (target <= here) target = if (door != -1) door - 1 else (here + 1).coerceAtMost(route.lastIndex)

            // A stop tile (before a door, or the end) must be reached closely: with the usual slack the wait would
            // already be satisfied and the loop would click again every pass.
            val slack = when {
                door != -1 && target == door - 1 -> 1
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
        return WebWalkResult(WebWalkStatus.STOPPED, "The script stopped while walking", path)
    }

    private fun plan(startX: Int, startY: Int, destX: Int, destY: Int, plane: Int, destPlane: Int, arriveDistance: Int): WebWalkResult {
        if (startX >= INSTANCE_MIN_X || destX >= INSTANCE_MIN_X) {
            return WebWalkResult(WebWalkStatus.NOT_IN_WORLD, "Web walking does not work inside instances")
        }
        if (plane != destPlane) {
            return WebWalkResult(WebWalkStatus.OTHER_FLOOR, "The destination is on plane $destPlane, the start on $plane")
        }
        return try {
            WebPathfinder().find(startX, startY, plane, destX, destY, arriveDistance)
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
