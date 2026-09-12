package com.projectx.game.map

import com.projectx.pathfinder.RebuildRegionMap
import com.projectx.pathfinder.WorldCollision
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.MapSquareType

/**
 * Per-tile map render flags ([world.gregs.voidps.map.RenderFlag]), resolved the same way for the overworld
 * and for dynamic instances. Overworld tiles read the source cache map square directly; instance tiles are
 * mapped back through the active [RebuildRegionMap] to their rotated source zone so the debug overlay shows
 * the real roof/clip data regardless of where the player is.
 */
object SceneRenderFlags {
    private val mapSquares = HashMap<Int, MapSquareType?>()

    fun renderFlags(x: Int, y: Int, plane: Int): Int {
        val source = instanceSource(x, y, plane)
        if (source != null) {
            val square = mapSquare(source.sourceMapSquareX, source.sourceMapSquareY) ?: return 0
            val (localX, localY) = inverseRotate(x and 7, y and 7, source.rotation)
            return square.surface.flags[source.level][source.sourceLocalZoneX * 8 + localX][source.sourceLocalZoneY * 8 + localY]
        }
        if (WorldCollision.inDynamic) return 0
        val square = mapSquare(x shr 6, y shr 6) ?: return 0
        return square.surface.flags[plane][x and 63][y and 63]
    }

    private fun instanceSource(x: Int, y: Int, plane: Int): RebuildRegionMap.Source? =
        if (WorldCollision.inDynamic) RebuildRegionMap.entryOf(x shr 3, y shr 3, plane) as? RebuildRegionMap.Source else null

    private fun mapSquare(mapSquareX: Int, mapSquareY: Int): MapSquareType? =
        mapSquares.getOrPut((mapSquareX shl 8) or mapSquareY) { Cache.mapSquare((mapSquareX shl 8) or mapSquareY) }

    private fun inverseRotate(virtualX: Int, virtualY: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
        1 -> 7 - virtualY to virtualX
        2 -> 7 - virtualX to 7 - virtualY
        3 -> virtualY to 7 - virtualX
        else -> virtualX to virtualY
    }
}
