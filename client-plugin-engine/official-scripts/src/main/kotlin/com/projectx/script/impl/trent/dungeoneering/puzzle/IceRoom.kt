package com.projectx.script.impl.trent.dungeoneering.puzzle

import com.projectx.pathfinder.RebuildRegionMap
import com.projectx.pathfinder.WorldCollision
import com.projectx.script.Script
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.localPlayer
import com.projectx.script.api.waitUntilNotMoving
import com.projectx.script.api.walkTo
import world.gregs.voidps.collision.CollisionFlag
import world.gregs.voidps.type.Tile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * All ice-room traversal for frozen floors lives HERE, never in the core dungeon navigator: a Daemonheim ice
 * room's whole floor carries you the way you walk until a wall/pillar stops you. Callers only ask [onIce] and
 * [slideTo]; no sliding concern leaks into the navigator/combat/analyzer.
 *
 * [slideTo] plans slides with the tested [IceSlide] BFS over the real instance [WorldCollision] (walls/pillars/
 * objects that stop a slide), so with collision loaded it plans the true route on the first pass. Tiles whose
 * collision hasn't streamed in yet (getFlags == -1, e.g. injected mid-dungeon) fall back to a blocked-map
 * LEARNED from physics — each slide that stops teaches exactly one blocker (the tile that stopped it) — and we
 * re-plan. So it still converges from any entry side even with partial/absent collision (e.g. reaching a wall
 * ladder sealed from above needs looping to slide in from below, which one "line up and slide" pass can't).
 */
object IceRoom {
    private const val ROOM_TILES = 16
    private const val ZONE_TILES = 8
    private const val MAX_SLIDES = 40
    private const val SLIDE_START_ID = 49331 // rand_slide_start "Snow" — a floor tile that marks an ice room

    // Source template rooms (from the REBUILD_REGION instance map) confirmed to be ice: learned the first time a
    // "Snow" marker is seen in a zone, then recognized anywhere by the zone's source base coords — so ice is
    // detected even when the marker loc isn't currently streamed into scan range, and across re-instancing.
    private val iceSourceZones = ConcurrentHashMap.newKeySet<Long>()

    fun onIce(): Boolean {
        val tile = localPlayer.tile
        learnIceSourceZonesFromMarkers(tile.plane)
        val room = roomOf(tile)
        if (getAllObjectsWithinRange(20).any { it.id == SLIDE_START_ID && roomOf(it.tile) == room }) return true
        return sourceZoneKey(tile.x / ZONE_TILES, tile.y / ZONE_TILES, tile.plane)?.let { it in iceSourceZones } ?: false
    }

    private fun learnIceSourceZonesFromMarkers(plane: Int) {
        getAllObjectsWithinRange(20)
            .filter { it.id == SLIDE_START_ID }
            .forEach { sourceZoneKey(it.tile.x / ZONE_TILES, it.tile.y / ZONE_TILES, plane)?.let(iceSourceZones::add) }
    }

    // Durable identity of the source zone a tile is instanced from (mapsquare + local zone + level, ignoring
    // rotation), or null if REBUILD_REGION for this zone hasn't been decoded (e.g. injected mid-dungeon).
    private fun sourceZoneKey(zoneX: Int, zoneY: Int, plane: Int): Long? {
        val src = RebuildRegionMap.entryOf(zoneX, zoneY, plane) as? RebuildRegionMap.Source ?: return null
        return (src.sourceMapSquareX.toLong() shl 24) or (src.sourceMapSquareY.toLong() shl 16) or
            (src.sourceLocalZoneX.toLong() shl 12) or (src.sourceLocalZoneY.toLong() shl 8) or src.level.toLong()
    }

    /** Slide up next to [target] (a ladder/door at a room wall). Returns true once we're adjacent to it. */
    suspend fun slideTo(script: Script, target: Tile): Boolean {
        val (rx, ry) = roomOf(localPlayer.tile)
        val minX = rx * ROOM_TILES
        val maxX = minX + ROOM_TILES - 1
        val minY = ry * ROOM_TILES
        val maxY = minY + ROOM_TILES - 1
        val plane = localPlayer.tile.plane
        val learnedBlocked = HashSet<Pair<Int, Int>>()
        val tried = HashSet<Pair<Pair<Int, Int>, SlideDir>>()
        val blocked = { x: Int, y: Int ->
            x < minX || x > maxX || y < minY || y > maxY ||
                (x to y) in learnedBlocked ||
                collisionBlocks(x, y, plane) == true
        }
        val goals = SlideDir.entries
            .map { Tile.of(target.x + it.dx, target.y + it.dy, plane) }
            .filter { !blocked(it.x, it.y) }

        repeat(MAX_SLIDES) {
            val here = localPlayer.tile
            if (here.getDistance(target) <= 1) return true
            val planned = goals.firstNotNullOfOrNull { IceSlide.path(here, it, blocked)?.firstOrNull() }
            val dir = planned ?: exploreDir(here, target, blocked, tried) ?: return false
            tried += (here.x to here.y) to dir

            walkTo(Tile.of(here.x + dir.dx * ROOM_TILES, here.y + dir.dy * ROOM_TILES, plane), false)
            script.waitUntilNotMoving()

            val after = localPlayer.tile
            val stalled = after.x == here.x && after.y == here.y
            // The tile one step past where we came to rest is what stopped the slide — a real blocker. Learn it.
            val rest = if (stalled) here else after
            learnedBlocked += (rest.x + dir.dx) to (rest.y + dir.dy)
        }
        return localPlayer.tile.getDistance(target) <= 1
    }

    // No usable plan yet (blocked-map still too sparse): slide the way that most reduces distance to the target,
    // skipping directions we've already tried from here or that are walled off, so we discover new blockers.
    private fun exploreDir(
        here: Tile,
        target: Tile,
        blocked: (Int, Int) -> Boolean,
        tried: Set<Pair<Pair<Int, Int>, SlideDir>>,
    ): SlideDir? {
        val dx = target.x - here.x
        val dy = target.y - here.y
        val preference = buildList {
            if (abs(dx) >= abs(dy)) {
                if (dx != 0) add(if (dx > 0) SlideDir.EAST else SlideDir.WEST)
                if (dy != 0) add(if (dy > 0) SlideDir.NORTH else SlideDir.SOUTH)
            } else {
                if (dy != 0) add(if (dy > 0) SlideDir.NORTH else SlideDir.SOUTH)
                if (dx != 0) add(if (dx > 0) SlideDir.EAST else SlideDir.WEST)
            }
            addAll(SlideDir.entries)
        }
        return preference.firstOrNull { dir ->
            (here.x to here.y) to dir !in tried && !blocked(here.x + dir.dx, here.y + dir.dy)
        }
    }

    // Real instance collision (rebuilt for dungeons): a tile holding a wall/pillar/object stops a slide. Returns
    // null when the zone hasn't streamed in yet (-1) so the caller defers to the learned-from-physics map.
    private fun collisionBlocks(x: Int, y: Int, plane: Int): Boolean? {
        val flags = WorldCollision.getFlags(x, y, plane)
        if (flags == -1) return null
        return flags and (CollisionFlag.FLOOR or CollisionFlag.FLOOR_DECORATION or CollisionFlag.OBJECT) != 0
    }

    private fun roomOf(tile: Tile): Pair<Int, Int> = tile.x / ROOM_TILES to tile.y / ROOM_TILES
}
