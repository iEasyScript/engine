package com.projectx.game.nxt.entity
import com.projectx.game.memory.atLeast
import com.projectx.game.nxt.extent

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess.getOrNull
import com.projectx.game.memory.NativeAccess.readFloat
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.NativeAccess.toMemorySegment
import com.projectx.game.memory.NativeAccess.toShared
import com.projectx.game.nxt.OClient
import com.projectx.game.nxt.OHintTrail
import com.projectx.game.nxt.OHintTrailList
import world.gregs.voidps.type.Tile
import java.lang.foreign.MemorySegment

private const val TILE_FINE = 512
private const val NO_TARGET_ID = -1
private const val UNPLACED_HEIGHT = -1.0f

/** One tile of a hint's path, in world-fine units. [height] is -1 until the tile's ground is loaded. */
class HintTrailPoint(val fineX: Float, val fineY: Float, val height: Float) {
    val placed get() = height != UNPLACED_HEIGHT
    fun tile(plane: Int) = Tile(fineX.toInt() / TILE_FINE, fineY.toInt() / TILE_FINE, plane)
}

/**
 * One occupied hint slot: the server's HINT_TRAIL packet fills these, and the client draws the map hint
 * icon at the end of the trail. A hint either walks to a coordinate (the trail points) or follows an
 * entity ([targetEntity]) - both live on this one object.
 */
class HintTrail(raw: MemorySegment) : Entity(raw) {

    val targetId: Int
        get() = runCatching { ptr.readInt(OHintTrail.TARGET_ID) }.getOrDefault(NO_TARGET_ID)

    val points: List<HintTrailPoint>
        get() = runCatching {
            val begin = ptr.readLong(OHintTrail.POINTS_BEGIN)
            val end = ptr.readLong(OHintTrail.POINTS_END)
            val stride = OHintTrail.POINT_STRIDE
            if (begin == 0L || end <= begin) return emptyList()
            val count = (end - begin) / stride
            if (count !in 1..MAX_POINTS) return emptyList()
            (0 until count).map { index ->
                val point = (begin + index * stride).toMemorySegment(stride)
                HintTrailPoint(
                    point.readFloat(OHintTrail.POINT_FINE_X),
                    point.readFloat(OHintTrail.POINT_FINE_Y),
                    point.readFloat(OHintTrail.POINT_FINE_HEIGHT),
                )
            }
        }.getOrDefault(emptyList())

    /** Where the hint is pointing: the end of the trail, which is the tile the map icon marks. */
    val destination: Tile?
        get() {
            val last = points.lastOrNull() ?: return targetEntity?.let { runCatching { it.tile }.getOrNull() }
            return runCatching { last.tile(plane) }.getOrNull()
        }

    val targetEntity: Entity?
        get() = runCatching {
            val raw = ptr.asSlice(OHintTrail.TARGET_ENTITY_SHARED_PTR, 0x10L).toShared().valueOrNull(EntityFactory.extent)
            raw?.let { EntityFactory.wrap(it) }
        }.getOrNull()

    companion object {
        /**
         * A trail is a tile-by-tile walk between the packet's coordinates, so it is naturally short. The
         * cap only exists so a torn begin/end pair can never turn into a multi-million element read.
         */
        private const val MAX_POINTS = 4096L

        /**
         * Every live hint, read straight out of the client's fixed hint-slot array.
         *
         * This is the whole point of the class: the slots are a bounded pointer array hanging off the
         * Client, so finding a hint costs [OHintTrailList.SLOT_COUNT] pointer reads. Walking the scene
         * graph looking for hint entities instead means dereferencing thousands of loosely validated
         * node pointers per sweep, which is a SIGSEGV waiting for a scene reload to happen underneath it.
         */
        fun all(): List<HintTrail> = runCatching {
            val container = Bootstrap.client.ptr.readLong(OClient.HINTTRAIL_LIST)
            if (container == 0L) return emptyList()
            val slots = container + OHintTrailList.SLOT_ARRAY
            (0 until OHintTrailList.SLOT_COUNT).mapNotNull { slot ->
                val entry = runCatching { (slots + slot * Long.SIZE_BYTES).toMemorySegment(8L).readLong(0L) }
                    .getOrDefault(0L)
                if (entry == 0L) return@mapNotNull null
                entry.toMemorySegment(ENTITY_BYTES).getOrNull?.let { HintTrail(it) }
            }
        }.getOrDefault(emptyList())

        /** Destinations of every live hint, nearest first. */
        fun destinations(): List<Tile> = all().mapNotNull { it.destination }

        private const val ENTITY_BYTES = 0x14b0L
    }

    override val size get() = 0
}
