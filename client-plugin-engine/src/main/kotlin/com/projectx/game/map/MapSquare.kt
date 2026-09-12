package com.projectx.game.map

import com.projectx.game.scene.CachedSceneObject
import com.projectx.pathfinder.WorldCollision
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.MapSquareType
import world.gregs.voidps.cache.type.data.MapSquareNpcSpawn
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.map.RenderFlag
import world.gregs.voidps.type.Tile
import java.lang.foreign.MemorySegment

class MapSquare(val mapSquareId: Int, load: Boolean = true) {

    companion object {
        private val mapSquares = mutableMapOf<Int, MapSquare>()

        fun get(mapSquareId: Int, load: Boolean = true): MapSquare {
            return mapSquares.getOrPut(mapSquareId) { MapSquare(mapSquareId, load) }.apply {
                if (load && !loaded) load()
            }
        }
    }

    var objects: Array<Array<Array<Array<CachedSceneObject?>>>>? = null
    var objectList: MutableList<CachedSceneObject>? = null
    var npcSpawns: List<MapSquareNpcSpawn>? = null
    private var loaded = false

    init {
        if (load) load()
    }

    fun load(): Boolean {
        val definition = Cache.mapSquare(mapSquareId)
        if (definition == null) {
            loaded = true
            return false
        }
        try {
            val mapSquareX = mapSquareId shr 8
            val mapSquareY = mapSquareId and 0xff
            decodeTiles(definition.surface.flags, mapSquareX, mapSquareY)
            decodeObjects(definition, mapSquareX, mapSquareY)
            npcSpawns = definition.npcSpawns
        } catch (t: Throwable) {
            loaded = true
            return false
        }
        loaded = true
        return true
    }

    private fun decodeTiles(tileFlags: Array<Array<IntArray>>, mapSquareX: Int, mapSquareY: Int) {
        for (plane in 0 until 4) {
            for (localX in 0 until 64) {
                for (localY in 0 until 64) {
                    if (!RenderFlag.flagged(tileFlags[plane][localX][localY], RenderFlag.CLIPPED)) continue
                    var finalPlane = plane
                    if (RenderFlag.flagged(tileFlags[1][localX][localY], RenderFlag.LOWER_OBJECTS_TO_OVERRIDE_CLIPPING)) {
                        finalPlane--
                    }
                    if (finalPlane >= 0) {
                        WorldCollision.addBlockedTile(Tile.of(localX + mapSquareX * 64, localY + mapSquareY * 64, finalPlane))
                    }
                }
            }
        }
    }

    private fun decodeObjects(definition: MapSquareType, mapSquareX: Int, mapSquareY: Int) {
        val tileFlags = definition.surface.flags
        for (obj in definition.objects) {
            val localX = obj.localX
            val localY = obj.localY
            val objectPlane = if (tileFlags[1][localX][localY] and 0x2 != 0) obj.plane - 1 else obj.plane
            if (objectPlane < 0) continue
            val shape = ObjectShape.forId(obj.shape)
            val cached = CachedSceneObject(
                MemorySegment.NULL,
                obj.id,
                obj.id,
                Tile.of(localX + mapSquareX * 64, localY + mapSquareY * 64, objectPlane),
                shape,
                obj.rotation.toByte()
            )
            spawnObject(cached, objectPlane, localX, localY)
        }
    }

    fun spawnObject(obj: CachedSceneObject, plane: Int, localX: Int, localY: Int) {
        if (objects == null) objects = Array(4) { Array(64) { Array(64) { arrayOfNulls(4) } } }
        if (objectList == null) objectList = mutableListOf()
        objectList!!.add(obj)
        objects!![plane][localX][localY][obj.slot] = obj
        WorldCollision.clip(obj)
    }
}
