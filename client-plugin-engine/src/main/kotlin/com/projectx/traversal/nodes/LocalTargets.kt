package com.projectx.traversal.nodes

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.pathfinder.LiveWalkMap
import com.projectx.pathfinder.WalkMap
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile

/**
 * Which object or NPC a node uses: the nearest by walking on the live map, measured to its footprint and the ring
 * around it, so one across a wall never wins over one round the corner. Where the map cannot tell, the nearest in a
 * straight line, still within the search radius.
 */
internal object LocalTargets {

    fun objectNear(radius: Int, predicate: (SceneObject) -> Boolean): SceneObject? {
        val me = localPlayer.tile
        val candidates = getAllObjectsWithinRange(me, radius).filter { it.tile.plane == me.plane && predicate(it) }
        return nearestByWalking(me, candidates, radius) { Footprint(it.tile, it.footprintX, it.footprintY) }
            ?: candidates.minByOrNull { it.tile.getDistance(me) }
    }

    fun npcNear(candidates: List<NPC>, radius: Int): NPC? {
        val me = localPlayer.tile
        val near = candidates.filter { it.tile.plane == me.plane }
        return nearestByWalking(me, near, radius) { Footprint(it.tile, 1, 1) } ?: near.minByOrNull { it.tile.getDistance(me) }
    }

    /** A target's south-west tile and size. */
    class Footprint(val tile: Tile, val sizeX: Int, val sizeY: Int)

    /** The candidate nearest [from] by walking to its footprint; null when the map cannot say or none is reachable. */
    fun <T> nearestByWalking(from: Tile, candidates: List<T>, radius: Int, footprint: (T) -> Footprint): T? {
        if (candidates.isEmpty()) return null
        val field = LiveWalkMap.field(from, (radius * 2 + 4).coerceAtMost(WalkMap.WINDOW_RADIUS)) ?: return null
        return candidates
            .map { candidate ->
                val f = footprint(candidate)
                candidate to field.distanceToFootprint(f.tile.x, f.tile.y, f.tile.plane, f.sizeX, f.sizeY)
            }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }
            ?.first
    }
}
