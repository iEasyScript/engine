package com.projectx.game.nxt.entity.location

import com.projectx.game.nxt.DoActionOpcode
import com.projectx.game.nxt.entity.GraphNode
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.type.data.LocType
import world.gregs.voidps.map.ObjectShape
import world.gregs.voidps.type.Tile
import java.lang.foreign.MemorySegment
import kotlin.math.hypot

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

    // Taken from the definition: the live type pointers are the morph source, null for most locs, and
    // reading them made every such loc a 1x1.
    /** Footprint width/length in tiles, pre-rotation. */
    val sizeX: Int get() = defs.sizeX
    val sizeY: Int get() = defs.sizeY

    /** Footprint extent along x and y once rotation is applied. */
    val footprintX: Int get() = (if (isRotated) sizeY else sizeX).coerceIn(1, 16)
    val footprintY: Int get() = (if (isRotated) sizeX else sizeY).coerceIn(1, 16)

    private val isRotated: Boolean get() = rotation.toInt() == 1 || rotation.toInt() == 3

    // Tile is an inline value class, so its accessors are name-mangled and unreachable from Java.
    val tileX: Int get() = tile.x
    val tileY: Int get() = tile.y
    val plane: Int get() = tile.plane

    /** Centre of the footprint in tile coordinates; a 3x3 rock centres one tile in from its origin. */
    val centerX: Double get() = tileX + (footprintX - 1) / 2.0
    val centerY: Double get() = tileY + (footprintY - 1) / 2.0

    /** Straight-line distance in tiles from the footprint centre to ([x], [y]). */
    fun distanceTo(x: Double, y: Double) = hypot(centerX - x, centerY - y)

    /**
     * World tiles this object's ground footprint covers. Origin [tile] is the SW corner; the
     * footprint spans [sizeX]×[sizeY] tiles with the dimensions swapped for odd rotations — matching
     * the engine's own collision footprint in [com.projectx.pathfinder.WorldCollision.clip].
     */
    fun occupiedTiles(): List<Tile> {
        val base = tile
        val width = footprintX
        val length = footprintY
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

    /** Uses [action] when the object offers it, otherwise its first option. */
    fun interactOrFirst(action: String) = interact(action) || interact(0)

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