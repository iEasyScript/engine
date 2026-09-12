package com.projectx.traversal.nodes

import world.gregs.voidps.type.Tile
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.profiling.PlayerProfiles
import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.traversal.TraversalNode
import com.projectx.util.gaussian
import com.projectx.util.random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

class ChebychevNode(
    val start: Tile,
    val end: List<Tile>,
    val fallBack: (() -> Boolean)? = null,
    val reached: (() -> Boolean)? = null
) : TraversalNode() {
    private var route: List<Tile>
    private var nextClick: Long = 0
    private var maxDistanceUsed: Int

    init {
        this.maxDistanceUsed = random(
            PlayerProfiles.get().futurePathStepMin,
            PlayerProfiles.get().futurePathStepMax
        )
        this.route = smoothPathChebyshev(start, end, maxDistanceUsed, fallBack)
    }

    private fun interpolateTilesChebyshevEx(a: Tile, b: Tile, maxDistance: Int, includeEnd: Boolean): List<Tile> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val chebyshev = max(abs(dx), abs(dy))
        val steps = ceil(chebyshev.toDouble() / maxDistance.toDouble()).toInt()
        if (steps <= 0) return if (includeEnd) listOf(b) else emptyList()

        val result = mutableListOf<Tile>()
        val last = if (includeEnd) steps else steps - 1
        for (i in 1..last) {
            val t = i.toDouble() / steps.toDouble()
            val x = round(a.x.toDouble() + dx.toDouble() * t).toInt()
            val y = round(a.y.toDouble() + dy.toDouble() * t).toInt()
            result.add(Tile.of(x, y, a.plane))
        }
        return result
    }

    fun smoothPathChebyshev(
        startingTile: Tile,
        steps: List<Tile>,
        maxDistance: Int,
        fallBack: (() -> Boolean)? = null
    ): List<Tile> {
        if (steps.isEmpty()) return listOf(startingTile)
        val result = mutableListOf<Tile>()

        var current = steps.first()
        result.add(current)

        for (next in steps) {
            if (current.withinDistance(next, maxDistance)) {
                result.add(next)
            } else {
                result.addAll(interpolateTilesChebyshevEx(current, next, maxDistance, true))
            }
            current = result.last()
        }

        if (fallBack != null) {
            var hasToTP = true
            for (tile in result) {
                if (localPlayer.tile.getDistance(tile) < PlayerProfiles.get().futurePathStepMax * 2) {
                    hasToTP = false
                    break
                }
            }
            if (hasToTP)
                fallBack.invoke()
        }

        return result
    }

    override suspend fun process(script: Script): Boolean {
        if (route.isEmpty()) {
            fallBack?.invoke()
            return false
        }

        if (System.currentTimeMillis() > nextClick) {
            val clickTile = getNextClickPoint()
            if (clickTile == null) {
                println("No click tile found")
                return true
            }
            walkTo(clickTile, random(100) >= PlayerProfiles.get().minimapWalkPerc)
            nextClick = System.currentTimeMillis() + gaussian(
                PlayerProfiles.get().walkPathClickTime, PlayerProfiles.get().walkPathClickTime / 2
            )
            script.delayUntil(8000) { clickTile.getDistance(localPlayer.tile) < 6 }
        }
        return true
    }

    fun getNextClickPoint(): Tile? {
        val path = route
        if (path.isEmpty()) return null

        val myPos = Bootstrap.client.loggedInPlayer.self.tile
        val closest = path.minByOrNull { myPos.getDistance(it) } ?: return null

        val futureTiles = path.dropWhile { !closest.matches(it) }
        if (futureTiles.isEmpty()) return null

        val within = futureTiles
            .takeWhile { myPos.withinDistance(it, round(maxDistanceUsed * 2.5).toInt()) }
            .ifEmpty { listOf(closest) }
        val target = within.last()
        return Tile.of(target, PlayerProfiles.get().walkPathDeviation)
    }

    override fun reached(script: Script): Boolean =
        reached?.invoke() == true || Bootstrap.client.loggedInPlayer.self.tile.getDistance(end.last()) <= 5

    override fun copy(): TraversalNode = ChebychevNode(start, end).also {
        it.maxDistanceUsed = this.maxDistanceUsed
        it.route = this.route
    }
}