package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.type.data.MapSquareEnvironmentFile
import world.gregs.voidps.cache.type.data.MapSquarePatch
import world.gregs.voidps.cache.type.data.MapSquarePointLight
import world.gregs.voidps.cache.type.data.MapSquareTerrain
import world.gregs.voidps.cache.type.data.MapSquareTerrainLevel
import world.gregs.voidps.cache.type.data.MapSquareTiles
import world.gregs.voidps.cache.type.decoder.MapSquareHeader
import world.gregs.voidps.cache.type.decoder.MapSquareTerrainDecoder

/** The inverse of `MapSquareTerrainDecoder`. */
object MapSquareTerrainEncoder {

    fun Writer.encode(terrain: MapSquareTerrain) {
        if (terrain.version != MapSquareTiles.NO_HEADER) {
            for (byte in MapSquareHeader.MAGIC) {
                writeByte(byte)
            }
            writeByte(terrain.version)
        }
        for (grid in terrain.levels) {
            writeByte(grid.level)
            for (x in 0 until MapSquareTerrainLevel.SIZE) {
                for (y in 0 until MapSquareTerrainLevel.SIZE) {
                    write(grid, MapSquareTerrainLevel.index(x, y))
                }
            }
        }
    }

    private fun Writer.write(grid: MapSquareTerrainLevel, cell: Int) {
        val populated = grid.populated[cell]
        val secondLayer = grid.secondLayer[cell]
        writeByte(MapSquareTerrainDecoder.flags(populated, grid.settings[cell], secondLayer))
        writeShort(grid.heights[cell])
        if (!populated) {
            return
        }
        if (secondLayer) {
            writeShort(grid.secondHeights[cell])
        }
        val underlay = grid.underlayIds[cell]
        writeSmart(underlay + 1)
        if (underlay != MapSquareTerrainLevel.NONE) {
            writeShort(grid.underlayColours[cell])
        }
        val overlay = grid.overlayIds[cell]
        writeSmart(overlay + 1)
        if (secondLayer) {
            writeSmart(grid.secondOverlayIds[cell] + 1)
        }
        if (overlay != MapSquareTerrainLevel.NONE) {
            writeByte((grid.overlayShapes[cell] shl 2) or grid.overlayRotations[cell])
            if (secondLayer) {
                writeSmart(grid.secondUnderlayIds[cell] + 1)
            }
        }
    }
}

/** The inverse of `MapSquareEnvironmentDecoder`. */
object MapSquareEnvironmentFileEncoder {

    fun Writer.encode(environment: MapSquareEnvironmentFile) {
        writeInt(environment.sunColour)
        environment.sunPosition.forEach { writeShort(it) }
        writeShort(environment.sunAmbient)
        writeShort(environment.sunLight)
        writeShort(environment.sunBacklight)
        environment.unknown5.forEach { writeFloat(it) }
        writeInt(environment.fogColour)
        writeShort(environment.fogDepth)
        writeByte(environment.fogEnabled)
        environment.unknown9.forEach { writeFloat(it) }
        writeByte(environment.unknown10)
        environment.unknown11.forEach { writeFloat(it) }
        environment.unknown12.forEach { writeFloat(it) }
        environment.unknown13.forEach { writeFloat(it) }
        writeInt(environment.unknown14)
        writeInt(environment.unknown15)
        writeFloat(environment.unknown16)
        writeByte(environment.unknown17)
        writeByte(environment.unknown18)
        environment.unknown19.forEach { writeFloat(it) }
        environment.unknown20.forEach { writeFloat(it) }
        writeShort(environment.unknown21)
        writeShort(environment.unknown22)
        writeByte(environment.unknown23)
        writeShort(environment.unknown24)
        writeFloat(environment.unknown25)
        writeShort(environment.unknown26)
        writeFloat(environment.unknown27)
        writeFloat(environment.unknown28)
        environment.unknown29.forEach { writeFloat(it) }
    }
}

/** The inverse of `MapSquarePointLightDecoder`. */
object MapSquarePointLightEncoder {

    fun Writer.encode(lights: List<MapSquarePointLight>) {
        writeByte(lights.size)
        for (light in lights) {
            writeByte(light.flags)
            writeShort(light.x)
            writeShort(light.z)
            writeShort(light.height)
            writeByte(light.radius)
            light.ranges.forEach { writeShort(it) }
            writeShort(light.colour)
            writeByte(light.packedType)
            writeShort(light.unknown7)
            if (light.packedType and 0x1f == MapSquarePointLight.CONFIG_TYPE) {
                writeShort(light.lightType)
            }
            light.unknown9.forEach { writeFloat(it) }
            writeByte(light.unknown10)
            writeFloat(light.unknown11)
            writeByte(light.unknown12)
            light.unknown13.forEach { writeFloat(it) }
            light.unknown14.forEach { writeShort(it) }
            writeByte(light.unknown15)
        }
    }
}

/** The inverse of `MapSquarePatchDecoder`. */
object MapSquarePatchEncoder {

    fun Writer.encode(patches: List<MapSquarePatch>) {
        writeByte(patches.size)
        for (patch in patches) {
            writeByte(patch.positionX)
            writeByte(patch.positionY)
            writeByte(patch.extentX)
            writeByte(patch.extentY)
            writeShort(patch.unknown4)
            patch.axis.forEach { writeFloat(it) }
            writeFloat(patch.angle)
            writeShort(patch.unknown6)
            writeByte(patch.directionX)
            writeByte(patch.directionY)
            writeShort(patch.typeId)
        }
    }
}
