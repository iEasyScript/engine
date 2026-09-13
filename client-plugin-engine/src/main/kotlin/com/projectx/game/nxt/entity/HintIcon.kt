package com.projectx.game.nxt.entity

import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess.readFloat
import com.projectx.game.memory.NativeAccess.readInt
import com.projectx.game.memory.NativeAccess.readLong
import com.projectx.game.memory.NativeAccess.toMemorySegment
import com.projectx.game.nxt.OClient
import com.projectx.game.nxt.OHintArrowDescriptor
import com.projectx.game.nxt.OHintArrowList
import com.projectx.script.api.localPlayer
import world.gregs.voidps.type.Tile

private const val TILE_FINE = 512
private const val COORDINATE_HINT = 2
private const val NO_TARGET = -1

/**
 * One live hint the server has placed - the marker the game drops on the map and draws in the world.
 *
 * A hint either names a coordinate or follows an entity ([targetIndex]); [tile] is where it points
 * either way.
 */
class HintIcon(
    val slot: Int,
    val kind: Int,
    val targetIndex: Int,
    val fineX: Float,
    val fineY: Float,
    val height: Float,
    plane: Int,
) {
    val tile: Tile = Tile(fineX.toInt() / TILE_FINE, fineY.toInt() / TILE_FINE, plane)

    val isCoordinate get() = kind == COORDINATE_HINT
    val hasTarget get() = targetIndex != NO_TARGET

    companion object {

        /**
         * Every live hint, read out of the client's fixed hint-slot arrays.
         *
         * The HINT_ARROW handler writes a POD descriptor per slot, so this costs one dereference off
         * the Client plus a fixed-stride index per slot - cheap enough to call every tick from
         * anywhere, and with no pointer chasing it cannot fault the way a scene-graph walk can.
         */
        @JvmStatic
        fun all(): List<HintIcon> = runCatching {
            val list = Bootstrap.client.ptr.readLong(OClient.HINTARROW_LIST)
            if (list == 0L) return emptyList()
            val plane = runCatching { localPlayer.tile.level }.getOrDefault(0)
            (0 until OHintArrowList.SLOT_COUNT).mapNotNull { slot ->
                if (slotPointer(list, slot) == 0L) null else descriptor(list, slot, plane)
            }
        }.getOrDefault(emptyList())

        /** The tiles the live hints point at. */
        @JvmStatic
        fun tiles(): List<Tile> = all().map { it.tile }

        /** A slot is live exactly when its arrow entity pointer is set; the clear path nulls it. */
        private fun slotPointer(list: Long, slot: Int) = runCatching {
            val address = list + OHintArrowList.ARROW_SLOTS + slot * Long.SIZE_BYTES
            address.toMemorySegment(Long.SIZE_BYTES.toLong()).readLong(0L)
        }.getOrDefault(0L)

        private fun descriptor(list: Long, slot: Int, plane: Int): HintIcon? = runCatching {
            val base = list + OHintArrowList.DESCRIPTORS + slot * OHintArrowList.DESCRIPTOR_STRIDE
            val record = base.toMemorySegment(OHintArrowList.DESCRIPTOR_STRIDE)
            val fineX = record.readFloat(OHintArrowDescriptor.FINE_X)
            val fineY = record.readFloat(OHintArrowDescriptor.FINE_Y)
            if (fineX < TILE_FINE || fineY < TILE_FINE) return null
            HintIcon(
                slot = slot,
                kind = record.readInt(OHintArrowDescriptor.KIND),
                targetIndex = record.readInt(OHintArrowDescriptor.TARGET_INDEX),
                fineX = fineX,
                fineY = fineY,
                height = record.readFloat(OHintArrowDescriptor.HEIGHT),
                plane = plane,
            )
        }.getOrNull()
    }
}
