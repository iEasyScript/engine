package com.projectx.traversal.walk

import com.projectx.pathfinder.LiveWalkMap
import com.projectx.script.Script
import com.projectx.script.api.localPlayer
import com.projectx.traversal.EarlyStart
import com.projectx.traversal.TraversalNode
import com.projectx.traversal.WalkEquivalent
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The live-map walk of a traversal node: hands over early to a next node that can start from a distance, and aims on
 * through the tiles of open doors and other passable nodes after it. Hand-built traversals never had hops, so none are
 * cast here.
 */
internal class NodeWalker(owner: String) {
    private val walker = LiveRouteWalker(owner).apply {
        surgeEnabled = false
        diveEnabled = false
        accelerationPotion = false
    }

    suspend fun step(node: TraversalNode, script: Script, own: List<Tile>, stepLimit: Int, done: () -> Boolean): LiveRouteWalker.Step {
        val player = localPlayer.tile
        val early = node.next as? EarlyStart
        if (early != null && LiveWalkMap.trusted(player) && early.readyFrom(player.x, player.y, player.plane)) return LiveRouteWalker.Step.ADVANCE
        return walker.step(script, extend(own, node), stepLimit, done)
    }

    fun reset() = walker.reset()

    companion object {
        private const val MAX_THROUGH_NODES = 4

        /** [own] plus the tiles of the passable, walk-through nodes after [node]. */
        fun extend(own: List<Tile>, node: TraversalNode): List<Tile> {
            var out: MutableList<Tile>? = null
            var next = node.next
            var hops = 0
            while (next != null && hops++ < MAX_THROUGH_NODES) {
                val walk = next as? WalkEquivalent ?: break
                if (!walk.walkThrough() || !walk.passableNow()) break
                val tiles = walk.routeTiles()
                if (tiles.isEmpty()) break
                if (out == null) out = own.toMutableList()
                out += tiles
                next = next.next
            }
            return out ?: own
        }

        /** One tile per step along straight lines between [points], for routes kept as sparse corners. */
        fun densify(points: List<Tile>): List<Tile> {
            if (points.size < 2) return points
            val out = ArrayList<Tile>()
            out += points.first()
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                if (a.plane != b.plane) {
                    out += b
                    continue
                }
                val steps = max(abs(b.x - a.x), abs(b.y - a.y))
                for (k in 1..steps) {
                    val t = k.toDouble() / steps
                    out += Tile.of((a.x + (b.x - a.x) * t).roundToInt(), (a.y + (b.y - a.y) * t).roundToInt(), a.plane)
                }
            }
            return out
        }
    }
}
