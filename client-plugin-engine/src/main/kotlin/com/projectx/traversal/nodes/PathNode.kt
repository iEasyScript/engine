package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile
import com.projectx.game.bootstrap.Bootstrap
import world.gregs.voidps.path.Route
import com.projectx.pathfinder.routeToTile
import world.gregs.voidps.path.toTiles
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import com.projectx.traversal.walk.LiveRouteWalker
import com.projectx.traversal.walk.NodeWalker
import com.projectx.util.gaussian
import com.projectx.util.random

class PathNode(val start: Tile, val end: Tile, val reached: (() -> Boolean)? = null) : TraversalNode(), WalkEquivalent {
    private var route: Route
    private var nextClick: Long = 0
    private val walker = NodeWalker("PathNode")

    init {
        this.route = routeToTile(start, end)
    }

    override suspend fun process(script: Script): Boolean {
        if (!route.success) {
            this.route = routeToTile(start, end)
            println("Recalculating path: ${this.route.success} ${this.route.alternative} ${this.route.distance}")
            script.delay(250, 250)
            return true
        }
        val stepLimit = random(PlayerProfiles.get().futurePathStepMin, PlayerProfiles.get().futurePathStepMax)
        when (walker.step(this, script, routeTiles(), stepLimit) { reached(script) || traversalStopRequested() }) {
            LiveRouteWalker.Step.CONTINUE -> return true
            LiveRouteWalker.Step.ADVANCE -> return false
            LiveRouteWalker.Step.STUCK -> if (requestTraversalRepath()) return true
            LiveRouteWalker.Step.FALLBACK -> {}
        }
        if (System.currentTimeMillis() > nextClick) {
            val clickTile = getNextClickPoint() ?: return true
            walkTo(clickTile, random(100) >= PlayerProfiles.get().minimapWalkPerc)
            nextClick = System.currentTimeMillis() + gaussian(PlayerProfiles.get().walkPathClickTime, PlayerProfiles.get().walkPathClickTime / 2)
        }
        return true
    }

    fun getNextClickPoint(): Tile? {
        val path = route.toTiles(localPlayer.plane)
        val myPos = Bootstrap.client.loggedInPlayer.self.tile
        var closest = Tile.of(0, 0, 0)

        closest = path.minByOrNull { myPos.getDistance(it) } ?: closest

        val futureTiles = path.dropWhile { !closest.matches(it) }

        val min = PlayerProfiles.get().futurePathStepMin
        val max = minOf(PlayerProfiles.get().futurePathStepMax, futureTiles.size)
        val numFuture = if (max <= min) max-1 else random(min, max)
        if (numFuture <= 1) return null

        val target = futureTiles[numFuture]
        var finalTarget: Tile? = null
        var tries = 20

        while (finalTarget == null && tries-- > 0) {
            val att = Tile.of(target, PlayerProfiles.get().walkPathDeviation)
            val route = routeToTile(target, att)
            if (route.success && route.distance <= PlayerProfiles.get().walkPathDeviation + 1) {
                finalTarget = att
            }
        }

        return finalTarget ?: target
    }

    override fun reached(script: Script): Boolean = reached?.invoke() == true || Bootstrap.client.loggedInPlayer.self.tile.getDistance(end) <= 5

    override fun routeTiles(): List<Tile> = if (route.success) route.toTiles(end.plane) else emptyList()

    override fun passableNow(): Boolean = true

    override fun copy(): TraversalNode = PathNode(start, end).also { it.route = this.route }
}