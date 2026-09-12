package com.projectx.game.nxt

import world.gregs.voidps.type.Tile
import com.projectx.game.bootstrap.Bootstrap
import com.projectx.game.memory.NativeAccess
import com.projectx.game.memory.NativeAccess.toMemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED

/**
 * Live terrain fine-height (Z) at a world-fine position, replicating `jag::game::HeightMap` bridge-aware
 * ground height without a native call: bilinear base vertex sample plus the additive link-height grid that
 * raised platforms/bridge decks store their elevation in. Coords are world-fine (512 units/tile;
 * 0x8000 = one 64-tile map square). Used by tile overlays so tiles sit on the real ground. All memory
 * offsets live in [OWorld]/[OHeightMap]/[ORegionHeightContainer].
 */
object HeightMap {
    private const val MAPSQUARE_FINE = 0x8000
    private const val TILE_FINE = 512
    private const val TILE_CENTRE = 256
    private const val SUBTILE_MASK = 0x1ff
    private const val SUBTILE_SHIFT = 9
    private const val RENDER_LIFT = 5

    private fun r64(addr: Long) = addr.toMemorySegment(8).get(JAVA_LONG_UNALIGNED, 0L)
    private fun r32(addr: Long) = addr.toMemorySegment(4).get(JAVA_INT_UNALIGNED, 0L)
    private fun r16(addr: Long) = addr.toMemorySegment(2).get(JAVA_SHORT_UNALIGNED, 0L)
    private fun r8(addr: Long) = addr.toMemorySegment(1).get(JAVA_BYTE, 0L)

    // A map-square container is loaded when its two state bytes match (the sampler uses the primary grid
    // only on byte[READY_LO] == byte[READY_HI]; otherwise it falls through to the fallback).
    private fun ready(c: Long) = c != 0L && r8(c + ORegionHeightContainer.READY_LO) == r8(c + ORegionHeightContainer.READY_HI)

    /** Terrain fine-height at [tile]'s centre, or null if the height map has no data there. */
    fun fineHeight(tile: Tile): Int? =
        fineHeight(tile.plane, tile.x * TILE_FINE + TILE_CENTRE, tile.y * TILE_FINE + TILE_CENTRE)

    /** Terrain fine-height (Z) at world-fine ([worldFineX], [worldFineY]) on [plane], or null. */
    fun fineHeight(plane: Int, worldFineX: Int, worldFineY: Int): Int? =
        runCatching { sample(plane, worldFineX, worldFineY) }.getOrNull()

    private fun sample(plane: Int, fineX: Int, fineY: Int): Int? {
        val world = (Bootstrap.client.sceneManager.currentWorld?.ptr ?: return null).address()
        val grid = r64(world + OWorld.HEIGHT_MAP + OHeightMap.GRID)
        if (grid == 0L) return null

        val mapSquareX = (if (fineX >= 0) fineX else fineX + (MAPSQUARE_FINE - 1)) shr 0xf
        val mapSquareY = (if (fineY >= 0) fineY else fineY + (MAPSQUARE_FINE - 1)) shr 0xf
        val withinX = fineX and (MAPSQUARE_FINE - 1)
        val withinY = fineY and (MAPSQUARE_FINE - 1)

        val minRX = r32(grid + OWorld.MAPSQUARE_X_OFFSET); val maxRX = r32(grid + OWorld.MAPSQUARE_X_MAX)
        val minRY = r32(grid + OWorld.MAPSQUARE_Y_OFFSET); val maxRY = r32(grid + OWorld.MAPSQUARE_Y_MAX)
        if (mapSquareX < minRX || mapSquareX > maxRX || mapSquareY < minRY || mapSquareY > maxRY) return null

        val dataArray = r64(grid + OWorld.MAPSQUARES_VECTOR)
        if (dataArray == 0L) return null
        val rowPtr = r64(dataArray + (mapSquareX - minRX).toLong() * OHeightMap.CELL_STRIDE)
        if (rowPtr == 0L) return null
        val cell = rowPtr + (mapSquareY - minRY).toLong() * OHeightMap.CELL_STRIDE
        if (r64(cell + OHeightMap.CELL_SENTINEL) == NativeAccess.BASE_ADDR.address() + OHeightMap.EMPTY_CELL_SENTINEL_REL) return null
        val mapSquareData = r64(cell + OHeightMap.CELL_CONTAINER)
        if (mapSquareData == 0L) return null

        var container = r64(mapSquareData + ORegionHeightContainer.PRIMARY)
        if (!ready(container)) {
            container = r64(mapSquareData + ORegionHeightContainer.FALLBACK)
            if (!ready(container)) return null
        }
        if (r8(container + ORegionHeightContainer.NOT_LOADED_FLAG).toInt() != 0) return null

        val planeBegin = r64(container + ORegionHeightContainer.PLANE_VEC_BEGIN)
        val planeCount = ((r64(container + ORegionHeightContainer.PLANE_VEC_END) - planeBegin) shr 4).toInt()
        if (plane < 0 || plane >= planeCount) return null
        var planeGrid = r64(planeBegin + ORegionHeightContainer.PLANE_ELEM + plane.toLong() * ORegionHeightContainer.PLANE_STRIDE)
        if (plane == 0) r64(container + ORegionHeightContainer.PLANE0_BRIDGE_GRID).let { if (it != 0L) planeGrid = it }
        if (planeGrid == 0L) return null

        val base = r64(planeGrid)
        if (base == 0L) return null
        val col = ((if (withinX >= 0) withinX else withinX + SUBTILE_MASK) shr SUBTILE_SHIFT) + 1
        val row = ((if (withinY >= 0) withinY else withinY + SUBTILE_MASK) shr SUBTILE_SHIFT) + 1
        val subX = withinX and SUBTILE_MASK
        val subY = withinY and SUBTILE_MASK

        fun h(c: Int, r: Int): Int = r32(r64(base + c.toLong() * ORegionHeightContainer.VERTEX_COL_STRIDE) + r.toLong() * ORegionHeightContainer.VERTEX_ROW_STRIDE)
        fun blend(a: Int, b: Int, t: Int): Int = (a * (0x200 - t) + b * t).let { if (it >= 0) it else it + SUBTILE_MASK } shr SUBTILE_SHIFT

        val height = when {
            subX == 0 && subY == 0 -> h(col, row)
            subX == 0 -> blend(h(col, row), h(col, row + 1), subY)
            subY == 0 -> blend(h(col, row), h(col + 1, row), subX)
            else -> blend(
                blend(h(col, row), h(col + 1, row), subX),
                blend(h(col, row + 1), h(col + 1, row + 1), subX),
                subY,
            )
        }
        if (height == -1) return null
        return height + linkHeightOffset(container, plane, withinX shr SUBTILE_SHIFT, withinY shr SUBTILE_SHIFT) + RENDER_LIFT
    }

    private fun linkHeightOffset(container: Long, plane: Int, col: Int, row: Int): Int {
        if (plane < 0 || plane >= r64(container + ORegionHeightContainer.LINK_HEIGHT_PLANE_COUNT)) return 0
        val grid = r64(container + ORegionHeightContainer.LINK_HEIGHT_GRID)
        if (grid == 0L) return 0
        val cell = grid + plane * ORegionHeightContainer.LINK_PLANE_STRIDE +
            col.coerceIn(0, 63) * ORegionHeightContainer.LINK_COL_STRIDE +
            row.coerceIn(0, 63) * ORegionHeightContainer.LINK_ROW_STRIDE
        return r16(cell).toInt()
    }
}
