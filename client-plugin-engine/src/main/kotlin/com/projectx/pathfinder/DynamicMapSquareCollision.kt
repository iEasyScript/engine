package com.projectx.pathfinder

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.nxt.OWorld
import com.projectx.game.nxt.World
import com.projectx.game.scene.CachedSceneObject
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.MapSquareObject
import world.gregs.voidps.cache.type.data.MapSquareType
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.map.RenderFlag
import world.gregs.voidps.type.Tile
import java.lang.foreign.MemorySegment

// The client keeps no walk-collision grid and its LinkMap grid is empty inside instances. Each virtual
// zone's static collision is rebuilt from its source-cache template — blocked floor tiles AND locs (walls,
// scenery), rotated by the REBUILD_REGION zone rotation, mirroring the server's copyInstanceZone. Live
// scene objects are then layered on top for dynamic locs (doors, debris); the visible dungeon walls are
// decorative (clipType 0) so the source-cache locs are the real wall collision.
object DynamicMapSquareCollision {
    private val LOCK = Any()

    @Volatile
    private var rebuildPending = true
    private var settleCalls = 0

    private val populatedZones = HashSet<Int>()
    private val sourceMapSquares = HashMap<Int, MapSquareType?>()
    private val sourceZoneObjectIndex = HashMap<Int, Map<Int, List<MapSquareObject>>>()

    // Zones touched by a single-loc spawn/despawn (ApplyLocChangeHook) since the last drain, mapped to a
    // short re-clip TTL. The client commits the Location a frame or two after the change event fires, so we
    // re-clip each dirty zone for a few ticks to catch the late commit. Coalesces a burst (e.g. Stomp
    // dropping ~40 debris in one tick) into a handful of keys instead of a rebuild per loc.
    private val dirtyZones = HashMap<Int, Int>()

    // A scene rebuild (BUILD_AREA_INIT / door-open) streams its locs in over several frames, so we
    // keep rebuilding for a short settle window after each markDirty to catch late-arriving locs.
    fun markDirty() = synchronized(LOCK) {
        rebuildPending = true
        settleCalls = 0
    }

    fun markZoneDirty(tileX: Int, tileY: Int, plane: Int) = synchronized(LOCK) {
        if (plane in 0..3) dirtyZones[zoneKey(tileX shr 3, tileY shr 3, plane)] = ZONE_RECLIP_TICKS
    }

    fun loadInstanceCollision() {
        synchronized(LOCK) {
            if (!rebuildPending && dirtyZones.isEmpty()) return
            val world = Bootstrap.client.sceneManager.currentWorld ?: return

            val minMapSquareX = world.mapsquareXOffset
            val minMapSquareY = world.mapsquareYOffset
            val maxMapSquareX = world.ptr.readInt(OWorld.MAPSQUARE_X_MAX)
            val maxMapSquareY = world.ptr.readInt(OWorld.MAPSQUARE_Y_MAX)
            if (maxMapSquareX < minMapSquareX || maxMapSquareY < minMapSquareY) return
            if (maxMapSquareX - minMapSquareX > MAX_BUILD_AREA_MAPSQUARES ||
                maxMapSquareY - minMapSquareY > MAX_BUILD_AREA_MAPSQUARES
            ) return

            if (rebuildPending) {
                rebuild(world, minMapSquareX, minMapSquareY, maxMapSquareX, maxMapSquareY)
                dirtyZones.clear()
                settleCalls++
                if (settleCalls >= SETTLE_CALLS) {
                    rebuildPending = false
                }
            } else {
                drainDirtyZones(world, minMapSquareX, minMapSquareY, maxMapSquareX, maxMapSquareY)
            }
        }
    }

    private fun rebuild(world: World, minX: Int, minY: Int, maxX: Int, maxY: Int) {
        clearPopulatedZones()
        for (mapSquareX in minX..maxX) {
            for (mapSquareY in minY..maxY) {
                for (localZoneX in 0 until 8) {
                    for (localZoneY in 0 until 8) {
                        val zoneX = mapSquareX * 8 + localZoneX
                        val zoneY = mapSquareY * 8 + localZoneY
                        for (plane in 0 until 4) {
                            WorldCollision.clearZone(zoneKey(zoneX, zoneY, plane))
                            populatedZones.add(zoneKey(zoneX, zoneY, plane))
                            applyFloorTerrain(zoneX, zoneY, plane)
                        }
                    }
                }
            }
        }

        for (mapSquareX in minX..maxX) {
            for (mapSquareY in minY..maxY) {
                val mapSquare = world.getMapSquare(mapSquareX, mapSquareY) ?: continue
                for (sceneObject in mapSquare.allSceneObjects) {
                    WorldCollision.clip(sceneObject)
                }
            }
        }
    }

    private fun drainDirtyZones(world: World, minX: Int, minY: Int, maxX: Int, maxY: Int) {
        val zonesByMapSquare = HashMap<Long, MutableList<Int>>()
        for (zoneKey in dirtyZones.keys) {
            val zoneX = (zoneKey ushr 11) and 0x7FF
            val zoneY = zoneKey and 0x7FF
            val mapSquareX = zoneX shr 3
            val mapSquareY = zoneY shr 3
            if (mapSquareX < minX || mapSquareX > maxX || mapSquareY < minY || mapSquareY > maxY) continue
            zonesByMapSquare.getOrPut((mapSquareX.toLong() shl 32) or mapSquareY.toLong()) { ArrayList() }.add(zoneKey)
        }

        for ((packedMapSquare, zoneKeys) in zonesByMapSquare) {
            val mapSquare = world.getMapSquare((packedMapSquare ushr 32).toInt(), (packedMapSquare and 0xFFFFFFFFL).toInt()) ?: continue
            for (zoneKey in zoneKeys) {
                WorldCollision.clearZone(zoneKey)
                applyFloorTerrain((zoneKey ushr 11) and 0x7FF, zoneKey and 0x7FF, (zoneKey ushr 22) and 0x3)
            }
            val dirtySet = zoneKeys.toHashSet()
            for (sceneObject in mapSquare.allSceneObjects) {
                val tile = sceneObject.tile
                if (zoneKey(tile.x shr 3, tile.y shr 3, tile.plane) in dirtySet) WorldCollision.clip(sceneObject)
            }
        }

        val iterator = dirtyZones.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= 1) iterator.remove() else entry.setValue(entry.value - 1)
        }
    }

    private fun applyFloorTerrain(zoneX: Int, zoneY: Int, plane: Int) {
        when (val entry = RebuildRegionMap.entryOf(zoneX, zoneY, plane)) {
            is RebuildRegionMap.Source -> applyMappedZone(entry, zoneX, zoneY, plane)
            RebuildRegionMap.Void -> blockZone(zoneX, zoneY, plane)
            null -> {}
        }
    }

    private fun applyMappedZone(source: RebuildRegionMap.Source, zoneX: Int, zoneY: Int, plane: Int) {
        val mapSquare = sourceMapSquare(source.sourceMapSquareX, source.sourceMapSquareY) ?: return
        val tileFlags = mapSquare.surface.flags
        val sourceBaseX = source.sourceLocalZoneX * 8
        val sourceBaseY = source.sourceLocalZoneY * 8

        for (localX in 0 until 8) {
            for (localY in 0 until 8) {
                val sourceX = sourceBaseX + localX
                val sourceY = sourceBaseY + localY
                if (!RenderFlag.flagged(tileFlags[source.level][sourceX][sourceY], RenderFlag.CLIPPED)) continue

                var finalPlane = plane
                if (RenderFlag.flagged(tileFlags[1][sourceX][sourceY], RenderFlag.LOWER_OBJECTS_TO_OVERRIDE_CLIPPING)) {
                    finalPlane--
                }
                if (finalPlane < 0) continue

                val (rotatedX, rotatedY) = rotate(localX, localY, source.rotation)
                WorldCollision.addBlockedTile(Tile.of(zoneX * 8 + rotatedX, zoneY * 8 + rotatedY, finalPlane))
            }
        }

        for (obj in sourceZoneObjects(source.sourceMapSquareX, source.sourceMapSquareY, source.level, source.sourceLocalZoneX, source.sourceLocalZoneY)) {
            val def = Cache.loc(obj.id) ?: continue
            var finalPlane = plane
            if (RenderFlag.flagged(tileFlags[1][obj.localX][obj.localY], RenderFlag.LOWER_OBJECTS_TO_OVERRIDE_CLIPPING)) {
                finalPlane--
            }
            if (finalPlane < 0) continue

            val (rotatedX, rotatedY) = rotateObject(obj.localX - sourceBaseX, obj.localY - sourceBaseY, source.rotation, def.sizeX, def.sizeY, obj.rotation)
            val tile = Tile.of(zoneX * 8 + rotatedX, zoneY * 8 + rotatedY, finalPlane)
            WorldCollision.clip(CachedSceneObject(MemorySegment.NULL, obj.id, obj.id, tile, ObjectShape.forId(obj.shape), ((obj.rotation + source.rotation) and 0x3).toByte()))
        }
    }

    private fun blockZone(zoneX: Int, zoneY: Int, plane: Int) {
        for (localX in 0 until 8) {
            for (localY in 0 until 8) {
                WorldCollision.addBlockedTile(Tile.of(zoneX * 8 + localX, zoneY * 8 + localY, plane))
            }
        }
    }

    private fun rotate(localX: Int, localY: Int, rotation: Int): Pair<Int, Int> = when (rotation) {
        1 -> localY to 7 - localX
        2 -> 7 - localX to 7 - localY
        3 -> 7 - localY to localX
        else -> localX to localY
    }

    private fun rotateObject(localX: Int, localY: Int, rotation: Int, sizeX: Int, sizeY: Int, objectRotation: Int): Pair<Int, Int> {
        val (width, length) = if (objectRotation and 0x1 == 1) sizeY to sizeX else sizeX to sizeY
        return when (rotation) {
            1 -> localY to 7 - localX - (width - 1)
            2 -> 7 - localX - (width - 1) to 7 - localY - (length - 1)
            3 -> 7 - localY - (length - 1) to localX
            else -> localX to localY
        }
    }

    private fun sourceMapSquare(mapSquareX: Int, mapSquareY: Int): MapSquareType? =
        sourceMapSquares.getOrPut((mapSquareX shl 8) or mapSquareY) {
            Cache.mapSquare((mapSquareX shl 8) or mapSquareY)
        }

    private fun sourceZoneObjects(mapSquareX: Int, mapSquareY: Int, level: Int, localZoneX: Int, localZoneY: Int): List<MapSquareObject> {
        val index = sourceZoneObjectIndex.getOrPut((mapSquareX shl 8) or mapSquareY) {
            val mapSquare = sourceMapSquare(mapSquareX, mapSquareY) ?: return@getOrPut emptyMap()
            mapSquare.objects.groupBy { sourceZoneBucket(it.plane, it.localX shr 3, it.localY shr 3) }
        }
        return index[sourceZoneBucket(level, localZoneX, localZoneY)] ?: emptyList()
    }

    private fun sourceZoneBucket(level: Int, localZoneX: Int, localZoneY: Int) = (level shl 6) or (localZoneX shl 3) or localZoneY

    private fun clearPopulatedZones() {
        for (zone in populatedZones) WorldCollision.clearZone(zone)
        populatedZones.clear()
    }

    fun clear() = synchronized(LOCK) {
        clearPopulatedZones()
        sourceMapSquares.clear()
        sourceZoneObjectIndex.clear()
        dirtyZones.clear()
        RebuildRegionMap.clear()
        rebuildPending = true
        settleCalls = 0
    }

    private fun zoneKey(zoneX: Int, zoneY: Int, plane: Int) = (zoneX shl 11) or zoneY or (plane shl 22)

    // A 255-zone (protocol-max) op93 region spans up to ~32 map squares, so the sanity bound on the
    // build-area offset span must cover that; a garbage offset read still yields a far larger/negative
    // span and is rejected. (Was 16 — too small, rejected larger quest-zone instances.)
    private const val MAX_BUILD_AREA_MAPSQUARES = 40
    private const val SETTLE_CALLS = 6
    private const val ZONE_RECLIP_TICKS = 4
}
