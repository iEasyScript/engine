package com.projectx.webwalker

import com.projectx.game.scene.CachedSceneObject
import com.projectx.pathfinder.WorldCollision
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.map.RenderFlag
import world.gregs.voidps.type.Tile
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

/**
 * Collision for route searches that reach beyond the scene.
 *
 * Squares are clipped into [WorldCollision] straight from the cache, without the per-square object tables
 * `MapSquare` keeps: a long route touches hundreds of squares, and those tables cost hundreds of kilobytes each.
 * Doors are indexed as they are decoded, since the search needs to know which walls it may open.
 */
internal object WebCollision {
    private const val SQUARE_TILES = 64
    private const val ZONE_TILES = 8

    private val DOOR_OPTIONS = listOf("Open", "Go-through", "Pass-through")

    /** Present when the cache holds the square; a missing square is ocean or void and never walkable. */
    private val squares = ConcurrentHashMap<Int, Boolean>()

    /** Planes whose zones were allocated, keyed `square shl 2 or plane`. */
    private val allocatedPlanes = ConcurrentHashMap.newKeySet<Int>()

    /** Closed-door walls keyed by tile id, holding the side of the tile the door stands on (0 west .. 3 south). */
    private val doors = ConcurrentHashMap<Int, Int>()

    fun squareId(x: Int, y: Int): Int = ((x shr 6) shl 8) or (y shr 6)

    /** Loads [squareId] for a search on [plane]; false when the square does not exist in the cache. */
    fun ensure(squareId: Int, plane: Int): Boolean {
        val exists = squares.computeIfAbsent(squareId) { load(it) }
        if (exists && allocatedPlanes.add((squareId shl 2) or plane)) allocateZones(squareId, plane)
        return exists
    }

    /** The side a door occupies on [tile] (0 west, 1 north, 2 east, 3 south), or -1 when there is none. */
    fun doorSide(x: Int, y: Int, plane: Int): Int = doors[Tile.of(x, y, plane).id] ?: -1

    fun loadedSquareCount(): Int = squares.size

    /** Every indexed door as tile id to side. */
    fun doors(): Map<Int, Int> = doors

    private fun load(squareId: Int): Boolean {
        val definition = Cache.mapSquare(squareId) ?: return false
        val baseX = (squareId shr 8) * SQUARE_TILES
        val baseY = (squareId and 0xff) * SQUARE_TILES
        val tileFlags = definition.surface.flags
        WorldCollision.loadMapSquareOnce(squareId) {
            for (plane in 0 until 4) for (localX in 0 until SQUARE_TILES) for (localY in 0 until SQUARE_TILES) {
                if (!RenderFlag.flagged(tileFlags[plane][localX][localY], RenderFlag.CLIPPED)) continue
                val finalPlane = if (RenderFlag.flagged(tileFlags[1][localX][localY], RenderFlag.LOWER_OBJECTS_TO_OVERRIDE_CLIPPING)) plane - 1 else plane
                if (finalPlane >= 0) WorldCollision.addBlockedTile(Tile.of(baseX + localX, baseY + localY, finalPlane))
            }
            for (obj in definition.objects) {
                val plane = objectPlane(tileFlags, obj.localX, obj.localY, obj.plane) ?: continue
                val tile = Tile.of(baseX + obj.localX, baseY + obj.localY, plane)
                WorldCollision.clip(CachedSceneObject(MemorySegment.NULL, obj.id, obj.id, tile, ObjectShape.forId(obj.shape), obj.rotation.toByte()))
            }
        }
        for (obj in definition.objects) {
            if (obj.shape != ObjectShape.WALL_STRAIGHT.id) continue
            val type = Cache.loc(obj.id) ?: continue
            if (type.clipType == 0 || DOOR_OPTIONS.none(type::containsOp)) continue
            val plane = objectPlane(tileFlags, obj.localX, obj.localY, obj.plane) ?: continue
            doors[Tile.of(baseX + obj.localX, baseY + obj.localY, plane).id] = obj.rotation and 3
        }
        return true
    }

    private fun objectPlane(tileFlags: Array<Array<IntArray>>, localX: Int, localY: Int, plane: Int): Int? {
        val lowered = if (tileFlags[1][localX][localY] and 0x2 != 0) plane - 1 else plane
        return lowered.takeIf { it >= 0 }
    }

    // A zone nothing was ever clipped into has no flag array, which the step checks read as fully blocked; open
    // ground would otherwise be unwalkable.
    private fun allocateZones(squareId: Int, plane: Int) {
        val baseX = (squareId shr 8) * SQUARE_TILES
        val baseY = (squareId and 0xff) * SQUARE_TILES
        for (zoneX in 0 until SQUARE_TILES step ZONE_TILES) for (zoneY in 0 until SQUARE_TILES step ZONE_TILES) {
            WorldCollision.addFlag(Tile.of(baseX + zoneX, baseY + zoneY, plane), 0)
        }
    }
}
