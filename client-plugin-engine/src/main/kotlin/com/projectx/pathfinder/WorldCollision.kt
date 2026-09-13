package com.projectx.pathfinder

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.map.MapSquare
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.api.inInstancedArea
import world.gregs.voidps.collision.ClipFlag
import world.gregs.voidps.collision.CollisionMap
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.type.Tile

object WorldCollision {
    private val map = CollisionMap()
    private val LOADED_MAPSQUARES = mutableSetOf<Int>()
    private val LOCK = Any()

    val allFlags: Array<IntArray?> get() = map.allFlags

    @Volatile
    var inDynamic = false
    var sceneBase: Tile? = null

    @JvmStatic
    fun checkLoad() {
        try {
            val tile = Bootstrap.client.loggedInPlayer.self.tile
            val dynamicMapSquare = inInstancedArea
            if (dynamicMapSquare && !inDynamic) {
                sceneBase = Tile(tile.mapSquareX shl 6, tile.mapSquareY shl 6, 0)
                inDynamic = true
            } else if (!dynamicMapSquare && inDynamic) {
                sceneBase = null
                inDynamic = false
                DynamicMapSquareCollision.clear()
            }
            if (dynamicMapSquare) {
                DynamicMapSquareCollision.loadInstanceCollision()
            } else if (tile.x > 0) {
                for (x in tile.mapSquareX - 4..tile.mapSquareX + 4)
                    for (y in tile.mapSquareY - 4..tile.mapSquareY + 4)
                        checkLoadMapSquare((x shl 8) + y)
            }
        } catch (_: Throwable) {
        }
    }

    @JvmStatic
    fun checkLoadMapSquare(mapSquareId: Int) {
        synchronized(LOCK) {
            MapSquare.get(mapSquareId, true)
            LOADED_MAPSQUARES.add(mapSquareId)
        }
    }

    /**
     * Runs [clip] for a square no loader has clipped yet and marks it loaded. A square already loaded may carry
     * live changes (an opened door unclipped), and clipping it from the cache again would undo them.
     */
    fun loadMapSquareOnce(mapSquareId: Int, clip: () -> Unit): Boolean = synchronized(LOCK) {
        if (!LOADED_MAPSQUARES.add(mapSquareId)) return false
        clip()
        true
    }

    @JvmStatic
    fun clearZone(zoneCollisionHash: Int) = map.clearZone(zoneCollisionHash)

    @JvmStatic
    fun removeFlag(tile: Tile, vararg flags: ClipFlag) = map.removeFlag(tile, *flags)

    @JvmStatic
    fun addFlag(tile: Tile, vararg flags: ClipFlag) = map.addFlag(tile, *flags)

    @JvmStatic
    fun setFlags(tile: Tile, vararg flags: ClipFlag) = map.setFlags(tile, *flags)

    @JvmStatic
    fun addBlockedTile(tile: Tile) = map.addBlockedTile(tile)

    @JvmStatic
    fun removeBlockedTile(tile: Tile) = map.removeBlockedTile(tile)

    @JvmStatic
    fun addBlockWalkAndProj(tile: Tile) = map.addBlockWalkAndProj(tile)

    @JvmStatic
    fun removeBlockWalkAndProj(tile: Tile) = map.removeBlockWalkAndProj(tile)

    @JvmStatic
    fun addClipNPC(tile: Tile) = map.addClipNPC(tile)

    @JvmStatic
    fun removeClipNPC(tile: Tile) = map.removeClipNPC(tile)

    @JvmStatic
    fun addClipPlayer(tile: Tile) = map.addClipPlayer(tile)

    @JvmStatic
    fun removeClipPlayer(tile: Tile) = map.removeClipPlayer(tile)

    @JvmStatic
    fun addObject(tile: Tile, sizeX: Int, sizeY: Int, blocksProjectiles: Boolean, pathfinder: Boolean) =
        map.addObject(tile, sizeX, sizeY, blocksProjectiles, pathfinder)

    @JvmStatic
    fun removeObject(tile: Tile, sizeX: Int, sizeY: Int, blocksProjectiles: Boolean, pathfinder: Boolean) =
        map.removeObject(tile, sizeX, sizeY, blocksProjectiles, pathfinder)

    @JvmStatic
    fun addWall(tile: Tile, type: ObjectShape?, rotation: Int, blocksProjectiles: Boolean, pathfinder: Boolean) =
        map.addWall(tile, type, rotation, blocksProjectiles, pathfinder)

    @JvmStatic
    fun removeWall(tile: Tile, type: ObjectShape?, rotation: Int, blocksProjectiles: Boolean, pathfinder: Boolean) =
        map.removeWall(tile, type, rotation, blocksProjectiles, pathfinder)

    @JvmStatic
    fun getFlags(tile: Tile): Int = map.getFlags(tile)

    @JvmStatic
    fun getFlags(x: Int, y: Int, plane: Int): Int = map.getFlags(x, y, plane)

    @JvmStatic
    fun addFlag(tile: Tile, flag: Int) = map.addFlag(tile, flag)

    @JvmStatic
    fun removeFlag(tile: Tile, flag: Int) = map.removeFlag(tile, flag)

    @JvmStatic
    fun setFlags(tile: Tile, flag: Int) = map.setFlags(tile, flag)

    @JvmStatic
    fun unclip(tile: Tile) = map.unclip(tile)

    @JvmStatic
    fun clip(obj: SceneObject) {
        if (obj.id == -1) return
        map.applyObject(obj.tile, obj.shape, obj.rotation.toInt(), obj.defs)
    }

    @JvmStatic
    fun unclip(obj: SceneObject) {
        if (obj.id == -1) return
        map.removeObjectClip(obj.tile, obj.shape, obj.rotation.toInt(), obj.defs)
    }
}
