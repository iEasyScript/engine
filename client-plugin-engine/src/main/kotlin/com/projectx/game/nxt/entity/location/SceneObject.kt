package com.projectx.game.nxt.entity.location

import com.projectx.game.nxt.DoActionOpcode
import com.projectx.game.nxt.entity.GraphNode
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.type.Tile
import java.lang.foreign.MemorySegment

private val MENU_OPS = arrayOf(
    DoActionOpcode.OBJECT_1,
    DoActionOpcode.OBJECT_2,
    DoActionOpcode.OBJECT_3,
    DoActionOpcode.OBJECT_4,
    DoActionOpcode.OBJECT_5,
    DoActionOpcode.OBJECT_6
)

interface SceneObject {
    val memPointer: MemorySegment
    val id: Int
    val typeId: Int
    val tile: Tile
    val shape: ObjectShape
    val rotation: Byte
    val defs: LocType
    val graphNode: GraphNode?

    val exists: Boolean

    /** Footprint width/length in tiles, pre-rotation. Defaults to 1 for implementations
     *  without a resolvable type; [Location]/[CombinedLocationSection] read the live LocType. */
    val sizeX: Int get() = 1
    val sizeY: Int get() = 1

    /**
     * World tiles this object's ground footprint covers. Origin [tile] is the SW corner; the
     * footprint spans [sizeX]×[sizeY] tiles with the dimensions swapped for odd rotations — matching
     * the engine's own collision footprint in [com.projectx.pathfinder.WorldCollision.clip].
     */
    fun occupiedTiles(): List<Tile> {
        val base = tile
        val rotated = rotation.toInt() == 1 || rotation.toInt() == 3
        // Cap guards against a garbage size from a stale/freed type pointer producing a huge loop.
        val width = (if (rotated) sizeY else sizeX).coerceIn(1, 16)
        val length = (if (rotated) sizeX else sizeY).coerceIn(1, 16)
        if (width == 1 && length == 1) return listOf(base)
        val plane = base.plane
        val tiles = ArrayList<Tile>(width * length)
        for (dx in 0 until width) for (dy in 0 until length) tiles += Tile.of(base.x + dx, base.y + dy, plane)
        return tiles
    }

    fun interact(action: Int): Boolean {
        if (action < 0 || action >= MENU_OPS.size) return false
        val action = MENU_OPS.getOrNull(action) ?: return false
        action.fire(id, tile.x, tile.y)
        return true
    }

    fun interact(action: String): Boolean {
        val op = getDef().getOpIdForName(action)
        return if (op != -1) {
            interact(op)
            true
        } else {
            false
        }
    }

    fun target(): Boolean {
        DoActionOpcode.SELECT_OBJECT.fire(id, tile.x, tile.y)
        return true
    }

    val slot: Int
        get() = shape.slot

    /** LocType id this object actually renders as, with any multi-loc transform applied.
     *  [LocTransform.HIDDEN] when the keying var selects the hidden slot. */
    val visibleTypeId: Int
        get() = LocTransform.resolve(if (typeId == -1) id else typeId)

    val isTransformHidden: Boolean
        get() = visibleTypeId == LocTransform.HIDDEN

    fun getDef(): LocType = Cache.loc(visibleTypeId) ?: LocType.EMPTY

    fun getName(): String = getDef().name

    fun hasOption(option: String): Boolean = getDef().containsOp(option)

    fun name(): String = getName()
}