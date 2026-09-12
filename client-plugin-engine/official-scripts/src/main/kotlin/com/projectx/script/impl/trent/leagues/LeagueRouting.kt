package com.projectx.script.impl.trent.leagues

import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

data class LeagueTaskRoute(
    val task: LeagueTask,
    val destination: Tile?,
    val distance: Int,
    val precise: Boolean,
    val effort: Int,
    val score: Double,
) {
    val located: Boolean get() = destination != null

    companion object {
        const val UNKNOWN_DISTANCE = 100_000
    }
}

/**
 * Orders tasks by how cheaply they can be finished, then by how far away they are.
 *
 * Effort dominates because a nearby thousand-log grind is not a better next task than a slightly
 * further one-off interaction. Repetition is scored on work *remaining* rather than the target, so
 * a task already at 900/1000 ranks near a fresh short one. Distance only separates tasks of similar
 * effort, and is damped logarithmically so it cannot outweigh a large effort gap.
 */
object LeagueRouting {

    private const val SCAN_RADIUS = 4

    /** A plane change costs roughly this many tiles of walking to find stairs and climb them. */
    private const val PLANE_PENALTY = 30

    private const val TIER_WEIGHT = 12.0
    private const val EFFORT_WEIGHT = 1.0
    private const val DISTANCE_WEIGHT = 14.0
    private const val UNLOCATED_PENALTY = 45.0

    fun rank(tasks: List<LeagueTask>, from: Tile): List<LeagueTaskRoute> {
        LeagueWorldIndex.scanAround(from, SCAN_RADIUS)
        return tasks.map { route(it, from) }.sortedBy { it.score }
    }

    fun route(task: LeagueTask, from: Tile): LeagueTaskRoute {
        val destination = nearestTarget(task, from)
        val distance = destination?.let { groundDistance(from, it.tile) } ?: LeagueTaskRoute.UNKNOWN_DISTANCE
        val effort = effortOf(task)
        return LeagueTaskRoute(
            task = task,
            destination = destination?.tile,
            distance = distance,
            precise = destination?.precise ?: false,
            effort = effort,
            score = score(task, effort, distance, destination != null),
        )
    }

    /**
     * Repetition counts dominate perceived effort, so they are scored on a log scale - the step
     * from 5 to 200 matters far more than the step from 800 to 1000.
     */
    fun effortOf(task: LeagueTask): Int {
        val outstanding = if (task.target > 0) task.remaining else 1
        return max(1, (ln(outstanding.toDouble() + 1.0) * 10).toInt())
    }

    private fun score(task: LeagueTask, effort: Int, distance: Int, located: Boolean): Double {
        val walk = ln(distance.toDouble().coerceAtLeast(1.0) + 1.0)
        return TIER_WEIGHT * (task.tier - 1) +
            EFFORT_WEIGHT * effort +
            DISTANCE_WEIGHT * walk +
            if (located) 0.0 else UNLOCATED_PENALTY
    }

    private class Destination(val tile: Tile, val precise: Boolean)

    private fun nearestTarget(task: LeagueTask, from: Tile): Destination? {
        val targets = task.targets
        if (targets.empty) return null
        LeagueWorldIndex.nearestLoc(targets.locIds, from)?.let { return Destination(it, precise = true) }
        LeagueNpcCoordinates.nearest(targets.npcIds, from)?.let { return Destination(it, precise = true) }
        LeagueWorldIndex.nearestNpcSquare(targets.npcIds, from)?.let { return Destination(it, precise = false) }
        return null
    }

    /**
     * Chebyshev distance ignoring plane, plus a fixed cost per level of separation. `Tile.distanceTo`
     * returns -1 across planes, which would otherwise sort upstairs targets to the very front.
     */
    fun groundDistance(from: Tile, to: Tile): Int =
        max(abs(from.x - to.x), abs(from.y - to.y)) + abs(from.level - to.level) * PLANE_PENALTY
}
