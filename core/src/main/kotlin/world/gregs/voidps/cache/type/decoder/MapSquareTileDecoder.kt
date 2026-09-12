package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.data.MapSquareEffect
import world.gregs.voidps.cache.type.data.MapSquareEnvironment
import world.gregs.voidps.cache.type.data.MapSquareHdr
import world.gregs.voidps.cache.type.data.MapSquareLight
import world.gregs.voidps.cache.type.data.MapSquareLightGrid
import world.gregs.voidps.cache.type.data.MapSquareLightGridLevel
import world.gregs.voidps.cache.type.data.MapSquareLights
import world.gregs.voidps.cache.type.data.MapSquareSkybox
import world.gregs.voidps.cache.type.data.MapSquareTiles
import world.gregs.voidps.cache.type.data.MapSquareType
import world.gregs.voidps.cache.type.data.MapSquareUnknown130
import world.gregs.voidps.cache.type.data.MapSquareUnknown3

/**
 * The lossless read of a terrain file: an optional magic header, `levels * 64 * 64` tile records in
 * level, localX, localY order, and - for a surface file - the environment block behind them.
 *
 * Nothing is skipped and nothing is derived, so what `MapSquareTileEncoder` writes back is the file
 * that came in.
 */
object MapSquareTileDecoder {

    fun decode(data: ByteArray, levels: Int): MapSquareTiles {
        val tiles = MapSquareTiles(levels)
        val buffer = BufferReader(data)
        tiles.version = MapSquareHeader.read(buffer)
        readTiles(buffer, tiles)
        readEnvironment(buffer, tiles)
        return tiles
    }

    private fun readTiles(buffer: Reader, tiles: MapSquareTiles) {
        for (level in 0 until tiles.levels) {
            for (x in 0 until MapSquareTiles.SIZE) {
                for (y in 0 until MapSquareTiles.SIZE) {
                    val records = buffer.readUnsignedByte()
                    tiles.records[level][x][y] = records
                    if (records and MapSquareTiles.OVERLAY != 0) {
                        val shape = buffer.readUnsignedByte()
                        tiles.overlayIds[level][x][y] = buffer.readUnsignedSmart()
                        tiles.overlayPathShapes[level][x][y] = shape shr 2
                        tiles.overlayRotations[level][x][y] = shape and 0x3
                    }
                    if (records and MapSquareTiles.SETTINGS != 0) {
                        tiles.flags[level][x][y] = buffer.readUnsignedByte()
                    }
                    if (records and MapSquareTiles.UNDERLAY != 0) {
                        tiles.underlayIds[level][x][y] = buffer.readUnsignedSmart()
                    }
                    if (records and MapSquareTiles.HEIGHT != 0) {
                        tiles.heights[level][x][y] = buffer.readUnsignedShort()
                    }
                }
            }
        }
    }

    private fun readEnvironment(buffer: Reader, tiles: MapSquareTiles) {
        if (buffer.remaining == 0) {
            return
        }
        tiles.environmentHead = IntArray(HEAD_SIZE) { buffer.readUnsignedByte() }
        while (buffer.remaining > 0) {
            tiles.effects.add(readEffect(buffer, buffer.readUnsignedByte()))
        }
    }

    private fun readEffect(buffer: Reader, opcode: Int): MapSquareEffect = when (opcode) {
        MapSquareEnvironment.OPCODE -> readEnvironmentSettings(buffer)
        MapSquareLights.OPCODE -> MapSquareLights(MutableList(buffer.readUnsignedByte()) { readLight(buffer) })
        MapSquareHdr.OPCODE -> MapSquareHdr(buffer.readFloat(), buffer.readFloat(), buffer.readFloat())
        MapSquareUnknown3.OPCODE -> MapSquareUnknown3(buffer.readUnsignedShort(), buffer.readFloat())
        MapSquareSkybox.OPCODE -> MapSquareSkybox(
            id = buffer.readUnsignedShort(),
            x = buffer.readShort(),
            y = buffer.readShort(),
            z = buffer.readShort(),
            rotation = buffer.readShort()
        )
        MapSquareLightGrid.OPCODE -> MapSquareLightGrid(
            MutableList(MapSquareType.LEVELS) {
                val mode = buffer.readUnsignedByte()
                val samples = if (mode == MapSquareLightGridLevel.MODE_UNLIT) {
                    null
                } else {
                    ByteArray(MapSquareLightGridLevel.SAMPLE_COUNT).also { buffer.readBytes(it) }
                }
                MapSquareLightGridLevel(mode, samples)
            }
        )
        MapSquareUnknown130.OPCODE -> MapSquareUnknown130()
        else -> throw IllegalStateException("Unknown map environment opcode $opcode at ${buffer.position()}")
    }

    private fun readEnvironmentSettings(buffer: Reader): MapSquareEnvironment {
        val flags = buffer.readUnsignedByte()
        val settings = MapSquareEnvironment()
        if (flags and 0x1 != 0) settings.sunColour = buffer.readInt()
        if (flags and 0x2 != 0) settings.sunAmbient = buffer.readUnsignedShort()
        if (flags and 0x4 != 0) settings.sunLight = buffer.readUnsignedShort()
        if (flags and 0x8 != 0) settings.sunBacklight = buffer.readUnsignedShort()
        if (flags and 0x10 != 0) {
            settings.sunPosition = IntArray(MapSquareEnvironment.SUN_POSITION_SIZE) { buffer.readShort() }
        }
        if (flags and 0x20 != 0) settings.fogColour = buffer.readInt()
        if (flags and 0x40 != 0) settings.fogDepth = buffer.readUnsignedShort()
        if (flags and 0x80 != 0) settings.unknown7 = buffer.readUnsignedShort()
        return settings
    }

    private fun readLight(buffer: Reader): MapSquareLight {
        val packedLevel = buffer.readUnsignedByte()
        val x = buffer.readUnsignedShort()
        val z = buffer.readUnsignedShort()
        val heightOffset = buffer.readUnsignedShort()
        val packedRadius = buffer.readUnsignedByte()
        val ranges = IntArray(packedRadius * 2 + 1) { buffer.readUnsignedShort() }
        val colour = buffer.readUnsignedShort()
        val packedType = buffer.readUnsignedByte()
        val unknown8 = buffer.readUnsignedShort()
        val lightType = if (packedType and 0x1f == MapSquareLight.CONFIG_TYPE) buffer.readUnsignedShort() else -1
        return MapSquareLight(packedLevel, x, z, heightOffset, packedRadius, ranges, colour, packedType, unknown8, lightType)
    }

    /** The bytes the environment block opens with, before its first record. */
    const val HEAD_SIZE = 8
}
