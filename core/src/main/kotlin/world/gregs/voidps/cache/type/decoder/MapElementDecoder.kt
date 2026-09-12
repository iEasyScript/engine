package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.MAP_ELEMENTS
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.Transforms.Companion.readVarbit
import world.gregs.voidps.cache.type.data.MapElementType
import world.gregs.voidps.cache.type.encoder.MapElementEncoder

class MapElementDecoder : ConfigDecoder<MapElementType>(MAP_ELEMENTS) {

    override fun create(size: Int) = Array(size) { MapElementType(it) }

    private val encoder = MapElementEncoder()

    override fun canonicalOpcodes(definition: MapElementType): IntArray = encoder.opcodes(definition)

    override fun MapElementType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> graphic = buffer.readBigSmart()
            2 -> graphicAlt = buffer.readBigSmart()
            3 -> name = buffer.readString()
            4 -> colour = buffer.readOpaqueArgb()
            5 -> colourAlt = buffer.readOpaqueArgb()
            6 -> field6 = buffer.readUnsignedByte()
            7 -> keep(opcode, buffer.capture {
                val flags = readUnsignedByte()
                field7a = flags and 0x1 != 0
                field7b = flags and 0x2 != 0
            })
            8 -> field8 = buffer.readUnsignedBoolean()
            9 -> {
                varbit9 = buffer.readVarbit(wide = false)
                varp9 = buffer.readNullableShort()
                field9c = buffer.readInt()
                field9d = buffer.readInt()
            }
            in 10..14 -> {
                val options = menuOptions ?: arrayOfNulls<String>(5).also { menuOptions = it }
                options[opcode - 10] = buffer.readString()
            }
            15 -> readPolygon(buffer)
            16 -> field16 = 0
            17 -> label = buffer.readString()
            18 -> field18 = buffer.readBigSmart()
            19 -> field19 = buffer.readUnsignedShort()
            20 -> {
                varbit20 = buffer.readVarbit(wide = false)
                varp20 = buffer.readNullableShort()
                field20c = buffer.readInt()
                field20d = buffer.readInt()
            }
            21 -> field21 = buffer.readArgb()
            22 -> field22 = buffer.readArgb()
            23 -> {
                field23a = buffer.readUnsignedByte()
                field23b = buffer.readUnsignedByte()
                field23c = buffer.readUnsignedByte()
            }
            24 -> {
                field24a = buffer.readTileUnits()
                field24b = buffer.readTileUnits()
            }
            25 -> field25 = buffer.readBigSmart()
            26, 27 -> readShortTransforms(buffer, opcode == 27, wideVarbit = false)
            28 -> field28 = buffer.readUnsignedByte()
            29 -> field29 = buffer.readUnsignedByte()
            30 -> field30 = buffer.readUnsignedByte()
            in 31..248 -> {}
            249 -> readParameters(buffer)
            250 -> {
                varbit9 = buffer.readVarbit(wide = true)
                varp9 = buffer.readNullableShort()
                field9c = buffer.readInt()
                field9d = buffer.readInt()
            }
            251 -> {
                varbit20 = buffer.readVarbit(wide = true)
                varp20 = buffer.readNullableShort()
                field20c = buffer.readInt()
                field20d = buffer.readInt()
            }
            252, 253 -> readShortTransforms(buffer, opcode == 253, wideVarbit = true)
            else -> unknown(opcode, buffer)
        }
    }

    private fun MapElementType.readPolygon(buffer: Reader) {
        val vertices = buffer.readUnsignedByte()
        val x = IntArray(vertices)
        val y = IntArray(vertices)
        for (vertex in 0 until vertices) {
            x[vertex] = buffer.readTileUnits()
            y[vertex] = buffer.readTileUnits()
        }
        polygonX = x
        polygonY = y
        polygonFillColour = buffer.readArgb()
        val palette = IntArray(buffer.readUnsignedByte())
        for (colour in palette.indices) {
            palette[colour] = buffer.readArgb()
        }
        polygonPalette = palette
        val vertexPalette = ByteArray(vertices)
        for (vertex in 0 until vertices) {
            vertexPalette[vertex] = buffer.readByte().toByte()
        }
        polygonVertexPalette = vertexPalette
    }

    private fun Reader.readNullableShort() = readUnsignedShort().let { if (it == 65535) -1 else it }

    private fun Reader.readOpaqueArgb() = readUnsignedMedium() or (0xff shl 24)

    private fun Reader.readArgb() = readInt().let { (it shl 8) or (it ushr 24) }

    private fun Reader.readTileUnits() = readShort() * UNITS_PER_TILE

    private companion object {
        const val UNITS_PER_TILE = 512
    }
}
