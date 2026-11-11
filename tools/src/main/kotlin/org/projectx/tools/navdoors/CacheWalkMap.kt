package org.projectx.tools.navdoors

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.collision.CollisionFlag
import world.gregs.voidps.collision.CollisionMap
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.map.RenderFlag
import world.gregs.voidps.type.Tile

/**
 * The world's collision as the cache places it, loaded a map square at a time as it is asked about, and read as
 * the route planner's walk masks. Built the way the engine's local planner builds its collision, so the two agree.
 */
internal class CacheWalkMap {
    private val collision = CollisionMap()
    private val loaded = HashSet<Int>()

    /**
     * The directions a player standing on the tile can step in, in the planner's bit order: 0 west, 1 south, 2 east,
     * 3 north, 4 north-west, 5 south-west, 6 south-east, 7 north-east. Zero for a tile nobody can stand on.
     */
    fun mask(x: Int, y: Int, plane: Int): Int {
        if (!standable(x, y, plane)) return 0
        val west = open(x - 1, y, plane, CollisionFlag.BLOCK_WEST)
        val south = open(x, y - 1, plane, CollisionFlag.BLOCK_SOUTH)
        val east = open(x + 1, y, plane, CollisionFlag.BLOCK_EAST)
        val north = open(x, y + 1, plane, CollisionFlag.BLOCK_NORTH)
        var mask = 0
        if (west) mask = mask or WEST
        if (south) mask = mask or SOUTH
        if (east) mask = mask or EAST
        if (north) mask = mask or NORTH
        if (west && north && open(x - 1, y + 1, plane, CollisionFlag.BLOCK_NORTH_WEST)) mask = mask or NORTH_WEST
        if (west && south && open(x - 1, y - 1, plane, CollisionFlag.BLOCK_SOUTH_WEST)) mask = mask or SOUTH_WEST
        if (east && south && open(x + 1, y - 1, plane, CollisionFlag.BLOCK_SOUTH_EAST)) mask = mask or SOUTH_EAST
        if (east && north && open(x + 1, y + 1, plane, CollisionFlag.BLOCK_NORTH_EAST)) mask = mask or NORTH_EAST
        return mask
    }

    private fun standable(x: Int, y: Int, plane: Int): Boolean {
        val flags = flags(x, y, plane)
        return flags != UNLOADED && flags and STANDING_BLOCKERS == 0
    }

    private fun open(x: Int, y: Int, plane: Int, blockers: Int): Boolean {
        val flags = flags(x, y, plane)
        return flags != UNLOADED && flags and blockers == 0
    }

    private fun flags(x: Int, y: Int, plane: Int): Int {
        // A wall on a square's edge clips the tile across it too, so the neighbouring squares are loaded as well.
        for (dx in -1..1) for (dy in -1..1) ensure((x shr 6) + dx, (y shr 6) + dy)
        return collision.getFlags(x, y, plane)
    }

    private fun ensure(squareX: Int, squareY: Int) {
        if (squareX < 0 || squareY < 0 || !loaded.add((squareX shl 8) or squareY)) return
        val square = Cache.mapSquare((squareX shl 8) or squareY) ?: return
        val baseX = squareX * SQUARE_TILES
        val baseY = squareY * SQUARE_TILES
        val tileFlags = square.surface.flags
        for (plane in 0 until PLANES) for (zoneX in 0 until SQUARE_TILES step ZONE_TILES) for (zoneY in 0 until SQUARE_TILES step ZONE_TILES) {
            collision.addFlag(Tile.of(baseX + zoneX, baseY + zoneY, plane), 0)
        }
        for (plane in 0 until PLANES) for (localX in 0 until SQUARE_TILES) for (localY in 0 until SQUARE_TILES) {
            if (!RenderFlag.flagged(tileFlags[plane][localX][localY], RenderFlag.CLIPPED)) continue
            val finalPlane = if (RenderFlag.flagged(tileFlags[1][localX][localY], RenderFlag.LOWER_OBJECTS_TO_OVERRIDE_CLIPPING)) plane - 1 else plane
            if (finalPlane >= 0) collision.addBlockedTile(Tile.of(baseX + localX, baseY + localY, finalPlane))
        }
        for (obj in square.objects) {
            val plane = if (tileFlags[1][obj.localX][obj.localY] and BRIDGE_FLAG != 0) obj.plane - 1 else obj.plane
            if (plane < 0) continue
            val type = Cache.loc(obj.id) ?: continue
            val shape = runCatching { ObjectShape.forId(obj.shape) }.getOrNull() ?: continue
            collision.applyObject(Tile.of(baseX + obj.localX, baseY + obj.localY, plane), shape, obj.rotation, type)
        }
    }

    companion object {
        const val WEST = 1 shl 0
        const val SOUTH = 1 shl 1
        const val EAST = 1 shl 2
        const val NORTH = 1 shl 3
        const val NORTH_WEST = 1 shl 4
        const val SOUTH_WEST = 1 shl 5
        const val SOUTH_EAST = 1 shl 6
        const val NORTH_EAST = 1 shl 7

        /** Each direction bit's step, in bit order. */
        val STEPS = arrayOf(-1 to 0, 0 to -1, 1 to 0, 0 to 1, -1 to 1, -1 to -1, 1 to -1, 1 to 1)

        private const val UNLOADED = -1
        private const val STANDING_BLOCKERS = CollisionFlag.OBJECT or CollisionFlag.FLOOR or CollisionFlag.FLOOR_DECORATION
        private const val SQUARE_TILES = 64
        private const val ZONE_TILES = 8
        private const val PLANES = 4
        private const val BRIDGE_FLAG = 0x2
    }
}
