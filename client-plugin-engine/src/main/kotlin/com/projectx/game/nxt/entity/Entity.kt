package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast

import com.projectx.game.nxt.extent
import com.projectx.game.nxt.OAnimation
import com.projectx.game.nxt.OGraphNode
import world.gregs.voidps.type.Tile
import com.projectx.game.memory.NativeAccess.deref
import com.projectx.game.memory.NativeAccess.pointerAtOffset
import com.projectx.game.memory.NativeAccess.readByte
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.nxt.EntityType
import com.projectx.game.nxt.OEntity
import com.projectx.game.math.Vector2f
import java.lang.foreign.MemorySegment
import kotlin.math.roundToInt

private const val MAX_ANIM_IDS = 16

abstract class Entity(raw: MemorySegment) {
    val ptr: MemorySegment = raw.atLeast(EntityFactory.extent)
    val graphNode: GraphNode
        get() = GraphNode(ptr.deref(OEntity.GRAPH_NODE, OGraphNode.extent))

    val type: EntityType
        get() = EntityType.fromType(ptr.readByte(OEntity.ENTITY_TYPE).toInt())

    open val tile: Tile
        get() {
            val size = size
            val fine = graphNode.tileFine
            val tileX = ((fine.x - 256f - (size shl 8).toFloat()) / 512f).roundToInt()
            val tileY = ((fine.y - 256f - (size shl 8).toFloat()) / 512f).roundToInt()
            return Tile.of(tileX, tileY, plane)
        }

    open val localTile: Tile
        get() {
            val tile = tile
            return Tile.of(tile.xInMapSquare, tile.yInMapSquare, plane)
        }

    open val size
        get() = ptr.readByte(OEntity.SIZE).toInt()

    val plane: Int
        get() = ptr.readInt(OEntity.ENTITY_PLANE)

    // Tile is an inline value class, so its accessors are name-mangled and unreachable from Java.
    open val tileX: Int
        get() = tile.x
    open val tileY: Int
        get() = tile.y

    /** Picking mode: 0 = point/circle test, non-zero = line segment test. */
    val pickType: Int
        get() = ptr.readInt(OEntity.PICK_TYPE)

    val screenCenterX: Int
        get() = ptr.readInt(OEntity.SCREEN_CENTER_X)

    val screenCenterY: Int
        get() = ptr.readInt(OEntity.SCREEN_CENTER_Y)

    val pointRadius: Int
        get() = ptr.readInt(OEntity.POINT_RADIUS)

    val screenLine1: Vector2f
        get() = Vector2f(ptr.readInt(OEntity.SCREEN_X1).toFloat(), ptr.readInt(OEntity.SCREEN_Y1).toFloat())

    val screenLine2: Vector2f
        get() = Vector2f(ptr.readInt(OEntity.SCREEN_X2).toFloat(), ptr.readInt(OEntity.SCREEN_Y2).toFloat())

    val lineRadius: Int
        get() = ptr.readInt(OEntity.LINE_RADIUS)

    val hasPickData: Boolean
        get() = pointRadius > 0 || lineRadius > 0

    val animationIds: List<Int>
        get() {
            val begin = ptr.readLong(OEntity.ANIM_IDS_BEGIN)
            val end = ptr.readLong(OEntity.ANIM_IDS_END)
            if (begin == 0L || end <= begin) return emptyList()
            val count = ((end - begin) / Int.SIZE_BYTES).toInt().coerceAtMost(MAX_ANIM_IDS)
            val ids = ptr.deref(OEntity.ANIM_IDS_BEGIN, (count * Int.SIZE_BYTES).toLong())
            return (0 until count).map { ids.readInt(it.toLong() * Int.SIZE_BYTES) }
        }

    val animationId: Int
        get() = ptr.readInt(OEntity.ANIMATION_ID)

    val animation: Animation?
        get() = ptr.pointerAtOffset(OEntity.ANIMATION_SHARED_PTR, 0x10L).toShared().valueOrNull(OAnimation.extent)?.let { Animation(it) }

    val spotAnims: List<SpotAnim>
        get() = graphNode.spotAnims

    val isAnimating: Boolean
        get() = animationId != -1

    val frameCount
        get() = graphNode.frameCount
}