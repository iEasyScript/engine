package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.data.MapSquareEnvironmentFile
import world.gregs.voidps.cache.type.data.MapSquarePatch
import world.gregs.voidps.cache.type.data.MapSquarePointLight

/** The lossless read of the environment file: one fixed record and nothing else. */
object MapSquareEnvironmentDecoder {

    fun decode(data: ByteArray): MapSquareEnvironmentFile {
        val buffer = BufferReader(data)
        return MapSquareEnvironmentFile(
            sunColour = buffer.readInt(),
            sunPosition = IntArray(MapSquareEnvironmentFile.VECTOR) { buffer.readShort() },
            sunAmbient = buffer.readUnsignedShort(),
            sunLight = buffer.readUnsignedShort(),
            sunBacklight = buffer.readUnsignedShort(),
            unknown5 = buffer.floats(3),
            fogColour = buffer.readInt(),
            fogDepth = buffer.readUnsignedShort(),
            fogEnabled = buffer.readUnsignedByte(),
            unknown9 = buffer.floats(4),
            unknown10 = buffer.readUnsignedByte(),
            unknown11 = buffer.floats(4),
            unknown12 = buffer.floats(3 * MapSquareEnvironmentFile.VECTOR),
            unknown13 = buffer.floats(8),
            unknown14 = buffer.readInt(),
            unknown15 = buffer.readInt(),
            unknown16 = buffer.readFloat(),
            unknown17 = buffer.readUnsignedByte(),
            unknown18 = buffer.readUnsignedByte(),
            unknown19 = buffer.floats(5),
            unknown20 = buffer.floats(6),
            unknown21 = buffer.readShort(),
            unknown22 = buffer.readShort(),
            unknown23 = buffer.readUnsignedByte(),
            unknown24 = buffer.readShort(),
            unknown25 = buffer.readFloat(),
            unknown26 = buffer.readShort(),
            unknown27 = buffer.readFloat(),
            unknown28 = buffer.readFloat(),
            unknown29 = buffer.floats(MapSquareEnvironmentFile.VECTOR)
        )
    }
}

/** The lossless read of the light file: a count and that many point lights. */
object MapSquarePointLightDecoder {

    fun decode(data: ByteArray): MutableList<MapSquarePointLight> {
        val buffer = BufferReader(data)
        if (buffer.remaining == 0) {
            return ArrayList(0)
        }
        return MutableList(buffer.readUnsignedByte()) { readLight(buffer) }
    }

    private fun readLight(buffer: Reader): MapSquarePointLight {
        val flags = buffer.readUnsignedByte()
        val x = buffer.readUnsignedShort()
        val z = buffer.readUnsignedShort()
        val height = buffer.readUnsignedShort()
        val radius = buffer.readUnsignedByte()
        val ranges = IntArray(radius * 2 + 1) { buffer.readUnsignedShort() }
        val colour = buffer.readUnsignedShort()
        val packedType = buffer.readUnsignedByte()
        val unknown7 = buffer.readShort()
        val lightType = if (packedType and 0x1f == MapSquarePointLight.CONFIG_TYPE) {
            buffer.readUnsignedShort()
        } else {
            MapSquarePointLight.NO_LIGHT_TYPE
        }
        return MapSquarePointLight(
            flags, x, z, height, radius, ranges, colour, packedType, unknown7, lightType,
            unknown9 = buffer.floats(3),
            unknown10 = buffer.readUnsignedByte(),
            unknown11 = buffer.readFloat(),
            unknown12 = buffer.readUnsignedByte(),
            unknown13 = buffer.floats(4),
            unknown14 = IntArray(3) { buffer.readShort() },
            unknown15 = buffer.readUnsignedByte()
        )
    }
}

/** The lossless read of the patch file: a count and that many fixed size records. */
object MapSquarePatchDecoder {

    fun decode(data: ByteArray): MutableList<MapSquarePatch> {
        val buffer = BufferReader(data)
        if (buffer.remaining == 0) {
            return ArrayList(0)
        }
        return MutableList(buffer.readUnsignedByte()) {
            MapSquarePatch(
                positionX = buffer.readByte(),
                positionY = buffer.readByte(),
                extentX = buffer.readByte(),
                extentY = buffer.readByte(),
                unknown4 = buffer.readShort(),
                axis = buffer.floats(3),
                angle = buffer.readFloat(),
                unknown6 = buffer.readUnsignedShort(),
                directionX = buffer.readByte(),
                directionY = buffer.readByte(),
                typeId = buffer.readUnsignedShort()
            )
        }
    }
}

private fun Reader.floats(count: Int): FloatArray = FloatArray(count) { readFloat() }
