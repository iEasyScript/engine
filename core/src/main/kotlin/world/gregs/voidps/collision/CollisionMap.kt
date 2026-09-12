package world.gregs.voidps.collision

import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.type.Tile

/**
 * A grid of collision flags for every tile in the world, including instances.
 *
 * Flags are stored in a flat array of per-zone [IntArray]s. The outer array is
 * indexed by [Tile.zoneId] (`2048 x 2048 x 4` possible zones) and the inner
 * array (64 entries, one per tile in an 8 x 8 zone) is indexed by
 * `xInZone or (yInZone shl 3)`. Zones are allocated lazily, so an empty map
 * costs only the outer array (~67MB) rather than a full dense grid.
 *
 * Instance-based: the server holds its own map; the engine keeps its singleton.
 */
class CollisionMap {
    val allFlags: Array<IntArray?> = arrayOfNulls(ZONE_SIZE * ZONE_SIZE * 4)
    private val lock = Any()

    fun clearZone(zoneCollisionHash: Int) {
        synchronized(lock) {
            allFlags[zoneCollisionHash] = null
        }
    }

    fun removeFlag(tile: Tile, vararg flags: ClipFlag) {
        var flag = 0
        for (f in flags) flag = flag or f.flag
        removeFlag(tile, flag)
    }

    fun addFlag(tile: Tile, vararg flags: ClipFlag) {
        var flag = 0
        for (f in flags) flag = flag or f.flag
        addFlag(tile, flag)
    }

    fun setFlags(tile: Tile, vararg flags: ClipFlag) {
        var flag = 0
        for (f in flags) flag = flag or f.flag
        setFlags(tile, flag)
    }

    fun addBlockedTile(tile: Tile) {
        addFlag(tile, ClipFlag.PFBW_FLOOR)
    }

    fun removeBlockedTile(tile: Tile) {
        removeFlag(tile, ClipFlag.PFBW_FLOOR)
    }

    fun addBlockWalkAndProj(tile: Tile) {
        addFlag(tile, ClipFlag.PFBW_GROUND_DECO)
    }

    fun removeBlockWalkAndProj(tile: Tile) {
        removeFlag(tile, ClipFlag.PFBW_GROUND_DECO)
    }

    fun addClipNPC(tile: Tile) {
        addFlag(tile, ClipFlag.BW_NPC)
    }

    fun removeClipNPC(tile: Tile) {
        removeFlag(tile, ClipFlag.BW_NPC)
    }

    fun addClipPlayer(tile: Tile) {
        addFlag(tile, ClipFlag.BW_PLAYER)
    }

    fun removeClipPlayer(tile: Tile) {
        removeFlag(tile, ClipFlag.BW_PLAYER)
    }

    fun addObject(tile: Tile, sizeX: Int, sizeY: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        var flag = ClipFlag.BW_FULL.flag
        if (blocksProjectiles) flag = flag or ClipFlag.BP_FULL.flag
        if (pathfinder) flag = flag or ClipFlag.PF_FULL.flag
        for (tileX in tile.x until tile.x + sizeX) for (tileY in tile.y until tile.y + sizeY) addFlag(Tile(tileX, tileY, tile.plane), flag)
    }

    fun removeObject(tile: Tile, sizeX: Int, sizeY: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        var flag = ClipFlag.BW_FULL.flag
        if (blocksProjectiles) flag = flag or ClipFlag.BP_FULL.flag
        if (pathfinder) flag = flag or ClipFlag.PF_FULL.flag
        for (tileX in tile.x until tile.x + sizeX) for (tileY in tile.y until tile.y + sizeY) removeFlag(Tile(tileX, tileY, tile.plane), flag)
    }

    fun addWall(tile: Tile, type: ObjectShape?, rotation: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        when (type) {
            ObjectShape.WALL_STRAIGHT -> {
                when (rotation) {
                    0 -> {
                        addFlag(tile, ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(-1, 0, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                    }

                    1 -> {
                        addFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(0, 1, 0), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                    }

                    2 -> {
                        addFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(1, 0, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                    }

                    3 -> {
                        addFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(0, -1, 0), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder))
                    }
                }
            }

            ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_STRAIGHT_CORNER -> {
                when (rotation) {
                    0 -> {
                        addFlag(tile, ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(-1, 1, 0), ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder))
                    }

                    1 -> {
                        addFlag(tile, ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(1, 1, 0), ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder))
                    }

                    2 -> {
                        addFlag(tile, ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(1, -1, 0), ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder))
                    }

                    3 -> {
                        addFlag(tile, ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(-1, -1, 0), ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder))
                    }
                }
            }

            ObjectShape.WALL_WHOLE_CORNER -> {
                when (rotation) {
                    0 -> {
                        addFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(-1, 0, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(0, 1, 0), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                    }

                    1 -> {
                        addFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(0, 1, 0), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(1, 0, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                    }

                    2 -> {
                        addFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder) or ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(1, 0, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(0, -1, 0), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder))
                    }

                    3 -> {
                        addFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(0, -1, 0), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder))
                        addFlag(tile.transform(-1, 0, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                    }
                }
            }

            else -> {}
        }
    }

    fun removeWall(tile: Tile, type: ObjectShape?, rotation: Int, blocksProjectiles: Boolean, pathfinder: Boolean) {
        when (type) {
            ObjectShape.WALL_STRAIGHT -> {
                when (rotation) {
                    0 -> {
                        removeFlag(tile, ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(-1, 0, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                    }

                    1 -> {
                        removeFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(0, 1, 0), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                    }

                    2 -> {
                        removeFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(1, 0, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                    }

                    3 -> {
                        removeFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(0, -1, 0), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder))
                    }
                }
            }

            ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_STRAIGHT_CORNER -> {
                when (rotation) {
                    0 -> {
                        removeFlag(tile, ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(-1, 1, 0), ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder))
                    }

                    1 -> {
                        removeFlag(tile, ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(1, 1, 0), ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder))
                    }

                    2 -> {
                        removeFlag(tile, ClipFlag.blockSouthEast(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(1, -1, 0), ClipFlag.blockNorthWest(true, blocksProjectiles, pathfinder))
                    }

                    3 -> {
                        removeFlag(tile, ClipFlag.blockSouthWest(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(-1, -1, 0), ClipFlag.blockNorthEast(true, blocksProjectiles, pathfinder))
                    }
                }
            }

            ObjectShape.WALL_WHOLE_CORNER -> {
                when (rotation) {
                    0 -> {
                        removeFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(-1, 0, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(0, 1, 0), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                    }

                    1 -> {
                        removeFlag(tile, ClipFlag.blockNorth(true, blocksProjectiles, pathfinder) or ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(0, 1, 0), ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(1, 0, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                    }

                    2 -> {
                        removeFlag(tile, ClipFlag.blockEast(true, blocksProjectiles, pathfinder) or ClipFlag.blockSouth(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(1, 0, 0), ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(0, -1, 0), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder))
                    }

                    3 -> {
                        removeFlag(tile, ClipFlag.blockSouth(true, blocksProjectiles, pathfinder) or ClipFlag.blockWest(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(0, -1, 0), ClipFlag.blockNorth(true, blocksProjectiles, pathfinder))
                        removeFlag(tile.transform(-1, 0, 0), ClipFlag.blockEast(true, blocksProjectiles, pathfinder))
                    }
                }
            }

            else -> {}
        }
    }

    fun getFlags(tile: Tile): Int {
        synchronized(lock) {
            val zoneId = tile.zoneId
            val zone = allFlags[zoneId] ?: return -1
            return zone[tile.xInZone or (tile.yInZone shl 3)]
        }
    }

    fun getFlags(x: Int, y: Int, plane: Int): Int {
        synchronized(lock) {
            val zoneId = ((x shr 3) shl 11) or (y shr 3) or (plane shl 22)
            val zone = allFlags[zoneId] ?: return -1
            return zone[(x and 7) or ((y and 7) shl 3)]
        }
    }

    fun addFlag(tile: Tile, flag: Int) {
        synchronized(lock) {
            val zoneId = tile.zoneId
            val zone = allFlags[zoneId] ?: IntArray(64).also { allFlags[zoneId] = it }
            val index = tile.xInZone or (tile.yInZone shl 3)
            zone[index] = zone[index] or flag
        }
    }

    fun removeFlag(tile: Tile, flag: Int) {
        synchronized(lock) {
            val zoneId = tile.zoneId
            val zone = allFlags[zoneId] ?: IntArray(64).also { allFlags[zoneId] = it }
            val index = tile.xInZone or (tile.yInZone shl 3)
            zone[index] = zone[index] and flag.inv()
        }
    }

    fun setFlags(tile: Tile, flag: Int) {
        synchronized(lock) {
            val zoneId = tile.zoneId
            val zone = allFlags[zoneId] ?: IntArray(64).also { allFlags[zoneId] = it }
            zone[tile.xInZone or (tile.yInZone shl 3)] = flag
        }
    }

    fun unclip(tile: Tile) {
        setFlags(tile, 0)
    }

    /**
     * Applies an object's collision geometry at [tile]. Mirrors the geometry the engine's
     * `WorldCollision.clip(SceneObject)` produced, reading [LocType.clipType],
     * [LocType.blocks], [LocType.ignoreAltClip] and the size fields.
     */
    fun applyObject(tile: Tile, shape: ObjectShape, rotation: Int, def: LocType) {
        if (def.clipType == 0) return

        when (shape) {
            ObjectShape.WALL_STRAIGHT, ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_WHOLE_CORNER, ObjectShape.WALL_STRAIGHT_CORNER -> addWall(tile, shape, rotation, def.blocks, !def.ignoreAltClip)
            ObjectShape.WALL_INTERACT, ObjectShape.SCENERY_INTERACT, ObjectShape.GROUND_INTERACT, ObjectShape.STRAIGHT_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF, ObjectShape.STRAIGHT_FLAT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_ROOF, ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_CORNER_ROOF -> {
                val sizeX: Int
                val sizeY: Int
                if (rotation != 1 && rotation != 3) {
                    sizeX = def.sizeX
                    sizeY = def.sizeY
                } else {
                    sizeX = def.sizeY
                    sizeY = def.sizeX
                }
                addObject(tile, sizeX, sizeY, def.blocks, !def.ignoreAltClip)
            }

            ObjectShape.GROUND_DECORATION -> if (def.clipType == 1) addBlockWalkAndProj(tile)
            else -> {}
        }
    }

    /**
     * Removes an object's collision geometry at [tile]. Inverse of [applyObject].
     */
    fun removeObjectClip(tile: Tile, shape: ObjectShape, rotation: Int, def: LocType) {
        if (def.clipType == 0) return

        when (shape) {
            ObjectShape.WALL_STRAIGHT, ObjectShape.WALL_DIAGONAL_CORNER, ObjectShape.WALL_WHOLE_CORNER, ObjectShape.WALL_STRAIGHT_CORNER -> removeWall(tile, shape, rotation, def.blocks, !def.ignoreAltClip)
            ObjectShape.WALL_INTERACT, ObjectShape.SCENERY_INTERACT, ObjectShape.GROUND_INTERACT, ObjectShape.STRAIGHT_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_ROOF, ObjectShape.DIAGONAL_SLOPE_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_CONNECT_ROOF, ObjectShape.STRAIGHT_SLOPE_CORNER_ROOF, ObjectShape.STRAIGHT_FLAT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_ROOF, ObjectShape.DIAGONAL_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_ROOF, ObjectShape.STRAIGHT_BOTTOM_EDGE_CONNECT_CORNER_ROOF -> {
                val sizeX: Int
                val sizeY: Int
                if (rotation == 1 || rotation == 3) {
                    sizeX = def.sizeY
                    sizeY = def.sizeX
                } else {
                    sizeX = def.sizeX
                    sizeY = def.sizeY
                }
                removeObject(tile, sizeX, sizeY, def.blocks, !def.ignoreAltClip)
            }

            ObjectShape.GROUND_DECORATION -> if (def.clipType == 1) removeBlockWalkAndProj(tile)
            else -> {}
        }
    }

    private companion object {
        // 2048 zones per axis = max capacity 16384x16384 tiles
        private const val ZONE_SIZE = 2048
    }
}
