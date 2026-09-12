package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.type.data.MapSquareEffect
import world.gregs.voidps.cache.type.data.MapSquareEnvironment
import world.gregs.voidps.cache.type.data.MapSquareHdr
import world.gregs.voidps.cache.type.data.MapSquareLight
import world.gregs.voidps.cache.type.data.MapSquareLightGrid
import world.gregs.voidps.cache.type.data.MapSquareLights
import world.gregs.voidps.cache.type.data.MapSquareSkybox
import world.gregs.voidps.cache.type.data.MapSquareTiles
import world.gregs.voidps.cache.type.data.MapSquareUnknown130
import world.gregs.voidps.cache.type.data.MapSquareUnknown3
import world.gregs.voidps.cache.type.decoder.MapSquareHeader

/** The inverse of `MapSquareTileDecoder`. */
object MapSquareTileEncoder {

    fun Writer.encode(tiles: MapSquareTiles) {
        if (tiles.version != MapSquareTiles.NO_HEADER) {
            for (byte in MapSquareHeader.MAGIC) {
                writeByte(byte)
            }
            writeByte(tiles.version)
        }
        for (level in 0 until tiles.levels) {
            for (x in 0 until MapSquareTiles.SIZE) {
                for (y in 0 until MapSquareTiles.SIZE) {
                    val records = tiles.records[level][x][y]
                    writeByte(records)
                    if (records and MapSquareTiles.OVERLAY != 0) {
                        writeByte((tiles.overlayPathShapes[level][x][y] shl 2) or tiles.overlayRotations[level][x][y])
                        writeSmart(tiles.overlayIds[level][x][y])
                    }
                    if (records and MapSquareTiles.SETTINGS != 0) {
                        writeByte(tiles.flags[level][x][y])
                    }
                    if (records and MapSquareTiles.UNDERLAY != 0) {
                        writeSmart(tiles.underlayIds[level][x][y])
                    }
                    if (records and MapSquareTiles.HEIGHT != 0) {
                        writeShort(tiles.heights[level][x][y])
                    }
                }
            }
        }
        val head = tiles.environmentHead ?: return
        for (byte in head) {
            writeByte(byte)
        }
        for (effect in tiles.effects) {
            writeByte(effect.opcode)
            write(effect)
        }
    }

    private fun Writer.write(effect: MapSquareEffect) {
        when (effect) {
            is MapSquareEnvironment -> {
                writeByte(effect.flags())
                effect.sunColour?.let { writeInt(it) }
                effect.sunAmbient?.let { writeShort(it) }
                effect.sunLight?.let { writeShort(it) }
                effect.sunBacklight?.let { writeShort(it) }
                effect.sunPosition?.forEach { writeShort(it) }
                effect.fogColour?.let { writeInt(it) }
                effect.fogDepth?.let { writeShort(it) }
                effect.unknown7?.let { writeShort(it) }
            }
            is MapSquareLights -> {
                writeByte(effect.lights.size)
                for (light in effect.lights) {
                    write(light)
                }
            }
            is MapSquareHdr -> {
                writeFloat(effect.bloom)
                writeFloat(effect.brightpass)
                writeFloat(effect.whitePoint)
            }
            is MapSquareUnknown3 -> {
                writeShort(effect.unknown0)
                writeFloat(effect.unknown1)
            }
            is MapSquareSkybox -> {
                writeShort(effect.id)
                writeShort(effect.x)
                writeShort(effect.y)
                writeShort(effect.z)
                writeShort(effect.rotation)
            }
            is MapSquareLightGrid -> for (level in effect.levels) {
                writeByte(level.mode)
                level.samples?.let { writeBytes(it) }
            }
            is MapSquareUnknown130 -> Unit
        }
    }

    private fun Writer.write(light: MapSquareLight) {
        writeByte(light.packedLevel)
        writeShort(light.x)
        writeShort(light.z)
        writeShort(light.heightOffset)
        writeByte(light.packedRadius)
        for (range in light.ranges) {
            writeShort(range)
        }
        writeShort(light.colour)
        writeByte(light.packedType)
        writeShort(light.unknown8)
        if (light.packedType and 0x1f == MapSquareLight.CONFIG_TYPE) {
            writeShort(light.lightType)
        }
    }
}
