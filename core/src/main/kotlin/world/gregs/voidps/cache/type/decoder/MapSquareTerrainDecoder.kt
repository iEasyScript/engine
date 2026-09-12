package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.data.MapSquareTerrain
import world.gregs.voidps.cache.type.data.MapSquareTerrainLevel
import world.gregs.voidps.cache.type.data.MapSquareTiles

/**
 * The lossless read of the terrain file: a magic header, then a level index and a 66 by 66 grid for
 * every level that has anything on it, running to the end of the file with no count and no
 * terminator.
 */
object MapSquareTerrainDecoder {

    fun decode(data: ByteArray): MapSquareTerrain {
        val terrain = MapSquareTerrain()
        val buffer = BufferReader(data)
        terrain.version = MapSquareHeader.read(buffer)
        while (buffer.remaining > 0) {
            terrain.levels.add(readLevel(buffer, buffer.readUnsignedByte()))
        }
        return terrain
    }

    private fun readLevel(buffer: Reader, level: Int): MapSquareTerrainLevel {
        val grid = MapSquareTerrainLevel(level)
        for (x in 0 until MapSquareTerrainLevel.SIZE) {
            for (y in 0 until MapSquareTerrainLevel.SIZE) {
                readCell(buffer, grid, MapSquareTerrainLevel.index(x, y))
            }
        }
        return grid
    }

    private fun readCell(buffer: Reader, grid: MapSquareTerrainLevel, cell: Int) {
        val flags = buffer.readUnsignedByte()
        grid.underlayIds[cell] = MapSquareTerrainLevel.NONE
        grid.overlayIds[cell] = MapSquareTerrainLevel.NONE
        grid.secondOverlayIds[cell] = MapSquareTerrainLevel.NONE
        grid.secondUnderlayIds[cell] = MapSquareTerrainLevel.NONE
        if (flags == 0) {
            grid.heights[cell] = buffer.readUnsignedShort()
            return
        }
        val secondLayer = flags and SECOND_LAYER != 0
        grid.populated[cell] = true
        grid.secondLayer[cell] = secondLayer
        grid.settings[cell] = ((flags shr 1) and 0x7) or (((flags shr 5) and 0x3) shl 3)
        grid.heights[cell] = buffer.readUnsignedShort()
        if (secondLayer) {
            grid.secondHeights[cell] = buffer.readUnsignedShort()
        }
        val underlay = buffer.readUnsignedSmart() - 1
        grid.underlayIds[cell] = underlay
        if (underlay != MapSquareTerrainLevel.NONE) {
            grid.underlayColours[cell] = buffer.readUnsignedShort()
        }
        val overlay = buffer.readUnsignedSmart() - 1
        grid.overlayIds[cell] = overlay
        if (secondLayer) {
            grid.secondOverlayIds[cell] = buffer.readUnsignedSmart() - 1
        }
        if (overlay != MapSquareTerrainLevel.NONE) {
            val shape = buffer.readUnsignedByte()
            grid.overlayShapes[cell] = shape shr 2
            grid.overlayRotations[cell] = shape and 0x3
            if (secondLayer) {
                grid.secondUnderlayIds[cell] = buffer.readUnsignedSmart() - 1
            }
        }
    }

    /** The flag bit that adds a second set of heights and ids to a cell. */
    const val SECOND_LAYER = 0x10

    /**
     * The flag byte a cell with these records is written from. Bit 0 marks a populated cell and is
     * set on every non-zero flag byte in the cache; the settings straddle bits 1 to 3 and 5 to 6.
     */
    fun flags(populated: Boolean, settings: Int, secondLayer: Boolean): Int {
        if (!populated) {
            return 0
        }
        var flags = 0x1 or ((settings and 0x7) shl 1) or (((settings shr 3) and 0x3) shl 5)
        if (secondLayer) {
            flags = flags or SECOND_LAYER
        }
        return flags
    }
}

/** The `jagx` magic and version byte the two terrain file kinds may open with. */
internal object MapSquareHeader {

    fun read(buffer: Reader): Int {
        if (buffer.remaining <= MAGIC.size) {
            return MapSquareTiles.NO_HEADER
        }
        val start = buffer.position()
        for (byte in MAGIC) {
            if (buffer.readUnsignedByte() != byte) {
                buffer.position(start)
                return MapSquareTiles.NO_HEADER
            }
        }
        return buffer.readUnsignedByte()
    }

    val MAGIC = "jagx".map { it.code }
}
