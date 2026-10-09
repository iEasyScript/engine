package com.projectx.webwalker

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.Lodestone
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.dive
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.isDiveReady
import com.projectx.script.api.localPlayer
import com.projectx.script.api.surge
import com.projectx.script.api.walkTo
import org.projectx.core.game.combat.Ability
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
 * Routes cross doors, opening them when they are shut, and take [WebLink]s - staircases, ladders, shortcuts, doors,
 * NPCs such as ferrymen and charter crews, and fairy rings - so a destination on another floor or across the sea is
 * reachable. A route may begin with a teleport when that gets there sooner: a lodestone, a carried teleport item, a
 * spell or the Passage of the Abyss.
 *
 * A link the account cannot use is left out of the search; see [WebLinkPermissions]. One that fails anyway - a flag
 * the engine could not check, a worn item it could not reach - is set aside for the rest of the walk and the route
 * is planned around it.
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
    private const val MAX_SET_ASIDE = 6

    // A staircase or shortcut runs an animation and may load a new area, so it is given longer than a door; a
    // teleport longer still, and a voyage the longest, since some sail through a cutscene.
    private const val LINK_TIMEOUT_MS = 12_000L
    private const val TELEPORT_TIMEOUT_MS = 25_000L
    private const val VOYAGE_TIMEOUT_MS = 40_000L

    // Teleports land near a point rather than on it, a lodestone furthest out.
    private const val TELEPORT_SLACK = 4
    private const val LODESTONE_SLACK = 10

    /** How long a link's "where to?" is given to appear before the answer is attempted anyway. */
    private const val CHOICE_TIMEOUT_MS = 4_000L
    private const val DOOR_SEARCH_RANGE = 6
    private const val ARRIVAL_SLACK = 3
    private const val STEP_TIMEOUT_MS = 700L
    private const val STALL_GRACE_MS = 1200L

    private val DOOR_OPTIONS = listOf("Open", "Go-through", "Pass-through")

    /**
     * Whether routes may spend teleport items and the Passage of the Abyss. On by default; turn it off in a script
     * that carries teleports for its own use, so the walker never spends a charge or a tab it was keeping.
     */
    @Volatile
    var useItemTeleports: Boolean = true

    /**
     * Whether walking may Dive and Surge along straight stretches of the route, as navpathService plans them. On by
     * default; turn it off in a script that saves those cooldowns for something else.
     */
    @Volatile
    var useMovementAbilities: Boolean = true

    // A movement ability hops at most ten tiles; Surge only ever goes its full distance, so it needs a straight run
    // that long, and the character must already face that way, which three steps walked in it settle.
    private const val ABILITY_REACH = 10
    private const val MIN_DIVE_TILES = 4
    private const val WALK_BEFORE_SURGE = 3
    private const val ABILITY_BARRIER_GAP = 2
    private const val ABILITY_LANDING_MS = 1_800L

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
    suspend fun findPath(script: Script, from: Tile, destination: Tile, arriveDistance: Int = 0): WebWalkResult =
        findPath(script, from, destination, arriveDistance, teleports = false, excluded = emptySet())

    private suspend fun findPath(
        script: Script,
        from: Tile,
        destination: Tile,
        arriveDistance: Int,
        teleports: Boolean,
        excluded: Set<WebLink>,
    ): WebWalkResult {
        // Requirements read varbits, levels, the money pouch and what is carried, which may only be touched here on
        // the game thread; the planner runs on its own thread and consults the snapshot instead.
        val permissions = runCatching { WebLinkPermissions.snapshot() }.getOrDefault(WebLinkPermissions.UNRESTRICTED)
        val future = CompletableFuture.supplyAsync(
            {
                plan(from.x, from.y, destination.x, destination.y, from.plane, destination.plane, arriveDistance, permissions, teleports, excluded)
            },
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
     * [useLodestones], a route may begin with a teleport when that gets there sooner: a lodestone, a spell, or - with
     * [useItemTeleports] - a carried teleport item or the Passage of the Abyss.
     */
    suspend fun walk(
        script: Script,
        destination: Tile,
        arriveDistance: Int = DEFAULT_ARRIVE_DISTANCE,
        useLodestones: Boolean = true,
    ): WebWalkResult {
        val arrive = arriveDistance.coerceAtLeast(0)
        val setAside = HashSet<WebLink>()
        if (!useItemTeleports) setAside += WebLinks.globals.filter { it.kind == WebLinkKind.ITEM || it.kind == WebLinkKind.POA }
        val keptBack = setAside.size
        var path: WebPath? = null
        var lodestone: Lodestone? = null
        var plans = 0
        var stalls = 0
        var doorAttempts = 0
        var linkAttempts = 0

        while (!script.stopped) {
            val me = localPlayer.tile
            if (arrived(destination, arrive)) {
                return WebWalkResult(WebWalkStatus.ARRIVED, "Arrived at ${destination.x},${destination.y}", path, lodestone)
            }

            if (path == null || path.distance(path.nearestIndex(me.x, me.y, me.plane), me.x, me.y) > OFF_PATH_DISTANCE || stalls >= MAX_STALLS) {
                if (++plans > MAX_PLANS) return WebWalkResult(WebWalkStatus.STUCK, "No progress after $MAX_PLANS routes", path, lodestone)
                val planned = findPath(script, me, destination, arrive, useLodestones, setAside)
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
                val step = route.linkAt(link)
                if (++linkAttempts > MAX_LINK_ATTEMPTS) {
                    if (step == null || setAside.size - keptBack >= MAX_SET_ASIDE) {
                        return WebWalkResult(WebWalkStatus.STUCK, "Could not take $step", route, lodestone)
                    }
                    println("[WebWalk] Could not take $step; planning around it")
                    setAside += step
                    linkAttempts = 0
                    path = null
                    continue
                }
                if (passLink(script, route, link)) {
                    linkAttempts = 0
                    if (step?.kind == WebLinkKind.LODESTONE) lodestone = step.lodestone
                }
                continue
            }

            if (door != -1 && door == barrier && route.distance(door - 1, me.x, me.y) <= 1) {
                if (++doorAttempts > MAX_DOOR_ATTEMPTS) {
                    return WebWalkResult(WebWalkStatus.STUCK, "Could not get through the door at ${route.getX(door)},${route.getY(door)}", route, lodestone)
                }
                if (passDoor(script, route, door)) doorAttempts = 0
                continue
            }

            if (useMovementAbilities && hopAhead(script, route, here, barrier)) continue

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

    /**
     * Dives, or failing that Surges, along the stretch of route ahead when it is straight enough and the ability is
     * ready; true when one was cast. Never within a couple of steps of a door or link, which have to be walked up to.
     */
    private suspend fun hopAhead(script: Script, route: WebPath, here: Int, barrier: Int): Boolean {
        val me = localPlayer.tile
        val last = (if (barrier == -1) route.lastIndex else barrier - 1 - ABILITY_BARRIER_GAP).coerceAtMost(here + ABILITY_REACH)
        if (last - here < MIN_DIVE_TILES) return false

        if (isDiveReady()) {
            val landing = (last downTo here + MIN_DIVE_TILES).firstOrNull { reachable(route, here, it, me) }
            if (landing != null) {
                val target = route.tile(landing)
                if (dive(target)) {
                    script.delayUntil(ABILITY_LANDING_MS) { chebyshev(localPlayer.tile.x, localPlayer.tile.y, target.x, target.y) <= 1 }
                    return true
                }
            }
        }

        if (Ability.SURGE.offCdIgnoreGCD && localPlayer.isMoving && here + ABILITY_REACH <= last &&
            straightRun(route, here - WALK_BEFORE_SURGE, here + ABILITY_REACH)
        ) {
            val target = route.tile(here + ABILITY_REACH)
            if (surge()) {
                script.delayUntil(ABILITY_LANDING_MS) { chebyshev(localPlayer.tile.x, localPlayer.tile.y, target.x, target.y) <= 2 }
                return true
            }
        }
        return false
    }

    /** A dive from [me] can reach step [to] when it is in range and the route there runs nearly straight. */
    private fun reachable(route: WebPath, from: Int, to: Int, me: Tile): Boolean {
        val target = route.tile(to)
        if (target.plane != me.plane || (from + 1..to).any { route.getPlane(it) != me.plane }) return false
        val dx = (target.x - me.x).toDouble()
        val dy = (target.y - me.y).toDouble()
        val distance = Math.sqrt(dx * dx + dy * dy)
        return distance <= ABILITY_REACH + 0.5 && (to - from) <= distance + 2.0
    }

    /** Every step from [from] to [to] goes the same way on one plane, so a hop along it lands where walking would. */
    private fun straightRun(route: WebPath, from: Int, to: Int): Boolean {
        if (from < 0 || to > route.lastIndex) return false
        val stepX = route.getX(from + 1) - route.getX(from)
        val stepY = route.getY(from + 1) - route.getY(from)
        return (from + 1..to).all {
            route.getX(it) - route.getX(it - 1) == stepX && route.getY(it) - route.getY(it - 1) == stepY &&
                route.getPlane(it) == route.getPlane(from) && route.linkAt(it) == null && !route.crossesDoor(it)
        }
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
        teleports: Boolean = false,
        excluded: Set<WebLink> = emptySet(),
    ): WebWalkResult {
        if (startX >= INSTANCE_MIN_X || destX >= INSTANCE_MIN_X) {
            return WebWalkResult(WebWalkStatus.NOT_IN_WORLD, "Web walking does not work inside instances")
        }
        return try {
            WebPathfinder().find(startX, startY, plane, destX, destY, destPlane, arriveDistance, permissions, teleports, excluded)
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
     * Takes the link at step [index]: walks onto the tile it starts from (a teleport starts wherever the player is),
     * does its first action, answers whatever it asks, and waits to arrive on the other side.
     *
     * Arrival is judged against the link's whole destination area rather than the one tile the route aimed at: a
     * staircase drops the player anywhere in the room at the top, and the route only picked a representative tile.
     */
    private suspend fun passLink(script: Script, path: WebPath, index: Int): Boolean {
        val link = path.linkAt(index) ?: return false
        if (!link.isGlobal) {
            val approach = path.tile(index - 1)
            val me = localPlayer.tile
            if (me != approach && me.plane == approach.plane && !localPlayer.isMoving) {
                walkTo(approach, false)
                script.delayUntil(STALL_GRACE_MS + STEP_TIMEOUT_MS * 3) { localPlayer.tile == approach }
            }
        }

        if (!WebLinkActions.start(script, link)) return false

        // Some ways through ask where to go and leave the player standing outside until that is answered.
        val choice = link.choice
        if (choice != null) {
            script.delayUntil(CHOICE_TIMEOUT_MS) { isDialogOpen() }
            if (!continueDialogueContaining(choice)) {
                println("[WebWalk] $link offered no \"$choice\" to pick")
                return false
            }
        }

        // A panel of destinations goes nowhere until one is picked, and a picked trapdoor still has to be climbed.
        for (step in link.chain) {
            if (!WebLinkActions.perform(script, link, step)) return false
        }

        val slack = arrivalSlack(link)
        script.delayUntil(arrivalTimeout(link)) { arrivedVia(link, slack) }
        val arrived = arrivedVia(link, slack)
        if (arrived) {
            script.delayUntil(STALL_GRACE_MS * if (link.isGlobal) 4 else 1) { !localPlayer.isMoving && !localPlayer.isAnimating }
        }
        return arrived
    }

    private fun arrivedVia(link: WebLink, slack: Int): Boolean {
        val tile = localPlayer.tile
        val to = link.to
        return tile.plane == to.plane && tile.x in to.minX - slack..to.maxX + slack && tile.y in to.minY - slack..to.maxY + slack
    }

    private fun arrivalSlack(link: WebLink): Int = when (link.kind) {
        WebLinkKind.OBJECT, WebLinkKind.DOOR -> 0
        WebLinkKind.LODESTONE -> LODESTONE_SLACK
        else -> TELEPORT_SLACK
    }

    private fun arrivalTimeout(link: WebLink): Long = when {
        link.kind == WebLinkKind.NPC -> VOYAGE_TIMEOUT_MS
        link.isGlobal || link.kind == WebLinkKind.FAIRY_RING -> TELEPORT_TIMEOUT_MS
        else -> LINK_TIMEOUT_MS
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
