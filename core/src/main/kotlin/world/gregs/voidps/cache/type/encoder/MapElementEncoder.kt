package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.Transforms
import world.gregs.voidps.cache.type.data.MapElementType

class MapElementEncoder : OpcodeEncoder<MapElementType>() {

    override fun opcodes(definition: MapElementType): IntArray {
        val opcodes = IntArrayList()
        if (definition.graphic != -1) {
            opcodes.add(1)
        }
        if (definition.graphicAlt != -1) {
            opcodes.add(2)
        }
        if (definition.name.isNotEmpty()) {
            opcodes.add(3)
        }
        if (definition.colour != 0) {
            opcodes.add(4)
        }
        if (definition.colourAlt != 0) {
            opcodes.add(5)
        }
        if (definition.field6 != 0) {
            opcodes.add(6)
        }
        if (definition.field8) {
            opcodes.add(8)
        }
        if (definition.varbit9 != -1 || definition.varp9 != -1) {
            opcodes.add(250)
        }
        val options = definition.menuOptions
        if (options != null) {
            for (index in options.indices) {
                if (options[index] != null) {
                    opcodes.add(10 + index)
                }
            }
        }
        if (definition.polygonX != null) {
            opcodes.add(15)
        }
        if (definition.field16 == 0) {
            opcodes.add(16)
        }
        if (definition.label.isNotEmpty()) {
            opcodes.add(17)
        }
        if (definition.field18 != -1) {
            opcodes.add(18)
        }
        if (definition.field19 != 0) {
            opcodes.add(19)
        }
        if (definition.varbit20 != -1 || definition.varp20 != -1) {
            opcodes.add(251)
        }
        if (definition.field21 != 0) {
            opcodes.add(21)
        }
        if (definition.field22 != 0) {
            opcodes.add(22)
        }
        if (definition.field23a != 0 || definition.field23b != 0 || definition.field23c != 0) {
            opcodes.add(23)
        }
        if (definition.field24a != 0 || definition.field24b != 0) {
            opcodes.add(24)
        }
        if (definition.field25 != -1) {
            opcodes.add(25)
        }
        if (definition.transforms != null) {
            opcodes.add(if (definition.transforms!!.last() != -1) 253 else 252)
        }
        if (definition.field28 != 0) {
            opcodes.add(28)
        }
        if (definition.field29 != 0) {
            opcodes.add(29)
        }
        if (definition.field30 != 0) {
            opcodes.add(30)
        }
        if (definition.params != null) {
            opcodes.add(249)
        }
        opcodes.addUnused(definition)
        return opcodes.toIntArray().also { it.sort() }
    }

    override fun Writer.encodeOpcode(definition: MapElementType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.graphic)
            2 -> writeBigSmart(definition.graphicAlt)
            3 -> writeString(definition.name)
            4 -> writeMedium(definition.colour and RGB)
            5 -> writeMedium(definition.colourAlt and RGB)
            6 -> writeByte(definition.field6)
            8 -> writeByte(definition.field8)
            9 -> {
                writeShort(if (definition.varbit9 == -1) NULL else definition.varbit9)
                writeShort(if (definition.varp9 == -1) NULL else definition.varp9)
                writeInt(definition.field9c)
                writeInt(definition.field9d)
            }
            in 10..14 -> writeString(definition.menuOptions?.get(opcode - 10))
            15 -> writePolygon(definition)
            16 -> Unit
            17 -> writeString(definition.label)
            18 -> writeBigSmart(definition.field18)
            19 -> writeShort(definition.field19)
            20 -> {
                writeShort(if (definition.varbit20 == -1) NULL else definition.varbit20)
                writeShort(if (definition.varp20 == -1) NULL else definition.varp20)
                writeInt(definition.field20c)
                writeInt(definition.field20d)
            }
            21 -> writeArgb(definition.field21)
            22 -> writeArgb(definition.field22)
            23 -> {
                writeByte(definition.field23a)
                writeByte(definition.field23b)
                writeByte(definition.field23c)
            }
            24 -> {
                writeShort(definition.field24a / UNITS_PER_TILE)
                writeShort(definition.field24b / UNITS_PER_TILE)
            }
            25 -> writeBigSmart(definition.field25)
            26, 27 -> writeShortTransforms(definition, opcode == 27, wideVarbit = false)
            28 -> writeByte(definition.field28)
            29 -> writeByte(definition.field29)
            30 -> writeByte(definition.field30)
            249 -> writeParams(definition)
            250 -> {
                writeMedium(if (definition.varbit9 == -1) Transforms.NULL_WIDE_VARBIT else definition.varbit9)
                writeShort(if (definition.varp9 == -1) NULL else definition.varp9)
                writeInt(definition.field9c)
                writeInt(definition.field9d)
            }
            251 -> {
                writeMedium(if (definition.varbit20 == -1) Transforms.NULL_WIDE_VARBIT else definition.varbit20)
                writeShort(if (definition.varp20 == -1) NULL else definition.varp20)
                writeInt(definition.field20c)
                writeInt(definition.field20d)
            }
            252, 253 -> writeShortTransforms(definition, opcode == 253, wideVarbit = true)
            else -> writeUnused(definition, opcode, occurrence)
        }
    }

    private fun Writer.writePolygon(definition: MapElementType) {
        val x = definition.polygonX ?: IntArray(0)
        val y = definition.polygonY ?: IntArray(0)
        writeByte(x.size)
        for (vertex in x.indices) {
            writeShort(x[vertex] / UNITS_PER_TILE)
            writeShort(y[vertex] / UNITS_PER_TILE)
        }
        writeArgb(definition.polygonFillColour)
        val palette = definition.polygonPalette ?: IntArray(0)
        writeByte(palette.size)
        for (colour in palette) {
            writeArgb(colour)
        }
        val vertexPalette = definition.polygonVertexPalette ?: ByteArray(0)
        for (entry in vertexPalette) {
            writeByte(entry.toInt())
        }
    }

    private fun Writer.writeArgb(value: Int) {
        writeInt((value ushr 8) or (value shl 24))
    }

    private companion object {
        const val NULL = 65535
        const val RGB = 0xffffff
        const val UNITS_PER_TILE = 512
    }
}
