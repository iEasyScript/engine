package com.projectx.script.api

import com.projectx.game.nxt.entity.Entity
import com.projectx.game.nxt.entity.location.SceneObject
import world.gregs.voidps.type.Tile

/**
 * A fixed point that an instanced arena is measured from. An instance lands somewhere new each time, so capture the
 * tile of something that always stands in the same place in it (an NPC or loc), then keep the layout as offsets from
 * that anchor. Offsets are plain translations: the arena is assumed not to be rotated.
 */
data class InstanceAnchor(val x: Int, val y: Int, val plane: Int) {

    constructor(tile: Tile) : this(tile.x, tile.y, tile.plane)

    val tile: Tile get() = Tile.of(x, y, plane)

    fun tile(dx: Int, dy: Int): Tile = Tile.of(worldX(dx), worldY(dy), plane)

    fun worldX(dx: Int): Int = x + dx

    fun worldY(dy: Int): Int = y + dy

    fun offsetX(worldX: Int): Int = worldX - x

    fun offsetY(worldY: Int): Int = worldY - y

    /** Whether world tile ([worldX], [worldY]) lies in the rectangle from offset ([minDx], [minDy]) to ([maxDx], [maxDy]), edges included. */
    fun contains(worldX: Int, worldY: Int, minDx: Int, minDy: Int, maxDx: Int, maxDy: Int): Boolean =
        offsetX(worldX) in minOf(minDx, maxDx)..maxOf(minDx, maxDx) &&
            offsetY(worldY) in minOf(minDy, maxDy)..maxOf(minDy, maxDy)

    /** Whether [tile] is on the anchor's plane and in the rectangle from offset ([minDx], [minDy]) to ([maxDx], [maxDy]). */
    fun contains(tile: Tile, minDx: Int, minDy: Int, maxDx: Int, maxDy: Int): Boolean =
        tile.plane == plane && contains(tile.x, tile.y, minDx, minDy, maxDx, maxDy)

    /** Whether the player stands in the rectangle from offset ([minDx], [minDy]) to ([maxDx], [maxDy]). */
    fun containsPlayer(minDx: Int, minDy: Int, maxDx: Int, maxDy: Int): Boolean =
        localPlayer.plane == plane && contains(localPlayer.tileX, localPlayer.tileY, minDx, minDy, maxDx, maxDy)

    fun area(minDx: Int, minDy: Int, maxDx: Int, maxDy: Int): Area.Rectangular =
        Area.Rectangular(worldX(minDx), worldY(minDy), worldX(maxDx), worldY(maxDy), plane)

    companion object {
        private const val DEFAULT_RANGE = 60

        @JvmStatic
        fun of(entity: Entity): InstanceAnchor = InstanceAnchor(entity.tileX, entity.tileY, entity.plane)

        /** Anchors on the origin (south-west) tile of [loc]. */
        @JvmStatic
        fun of(loc: SceneObject): InstanceAnchor = InstanceAnchor(loc.tileX, loc.tileY, loc.plane)

        /** Anchors on the nearest NPC with id [npcId] within [range] tiles; null when there is none. */
        @JvmStatic
        @JvmOverloads
        fun ofNpc(npcId: Int, range: Int = DEFAULT_RANGE): InstanceAnchor? = findClosestNPC(npcId, range)?.let { of(it) }

        /** Anchors on the nearest loc with id [locId] within [range] tiles; null when there is none. */
        @JvmStatic
        @JvmOverloads
        fun ofLoc(locId: Int, range: Int = DEFAULT_RANGE): InstanceAnchor? = findClosestObject(locId, range)?.let { of(it) }
    }
}
