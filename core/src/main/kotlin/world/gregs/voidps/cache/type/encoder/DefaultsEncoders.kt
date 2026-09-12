package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.DefaultsGroup10Type
import world.gregs.voidps.cache.type.data.DefaultsGroup12Type
import world.gregs.voidps.cache.type.data.DefaultsGroup1Type
import world.gregs.voidps.cache.type.data.DefaultsGroup2Type
import world.gregs.voidps.cache.type.data.DefaultsGroup3Type
import world.gregs.voidps.cache.type.data.DefaultsGroup4Type
import world.gregs.voidps.cache.type.data.DefaultsGroup5Type
import world.gregs.voidps.cache.type.data.DefaultsGroup6Type
import world.gregs.voidps.cache.type.data.DefaultsGroup7Type
import world.gregs.voidps.cache.type.data.DefaultsGroup8Type
import world.gregs.voidps.cache.type.data.DefaultsGroup9Type
import world.gregs.voidps.cache.type.data.DefaultsLight
import world.gregs.voidps.cache.type.data.DefaultsRamp
import world.gregs.voidps.cache.type.decoder.DefaultsGroup10Decoder
import world.gregs.voidps.cache.type.decoder.DefaultsGroup9Decoder

private fun Writer.writeCountedBytes(values: IntArray?) {
    val array = values ?: IntArray(0)
    writeByte(array.size)
    for (value in array) {
        writeByte(value)
    }
}

class DefaultsGroup1Encoder : OpcodeEncoder<DefaultsGroup1Type>() {
    override fun opcodes(definition: DefaultsGroup1Type): IntArray {
        val opcodes = IntArrayList()
        if (definition.unknown1 != -1) {
            opcodes.add(1)
        }
        if (definition.unknown10 != -1) {
            opcodes.add(10)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: DefaultsGroup1Type, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeShort(definition.unknown1)
            10 -> writeShort(definition.unknown10)
            else -> error("Unhandled defaults group 1 opcode $opcode in ${definition.id}")
        }
    }
}

class DefaultsGroup2Encoder : OpcodeEncoder<DefaultsGroup2Type>() {
    override fun opcodes(definition: DefaultsGroup2Type) = IntArray(0)
    override fun Writer.encodeOpcode(definition: DefaultsGroup2Type, opcode: Int, occurrence: Int) =
        error("Unhandled defaults group 2 opcode $opcode in ${definition.id}")
}

class DefaultsGroup3Encoder : OpcodeEncoder<DefaultsGroup3Type>() {

    override fun opcodes(definition: DefaultsGroup3Type): IntArray {
        val opcodes = IntArrayList()
        if (definition.pairCount != -1) {
            opcodes.add(3)
        }
        if (definition.pairs != null) {
            opcodes.add(1)
        }
        if (definition.unknown2 != null) {
            opcodes.add(2)
        }
        if (definition.unknown4) {
            opcodes.add(4)
        }
        if (definition.unknown5 != -1) {
            opcodes.add(5)
        }
        if (definition.unknown6 != -1) {
            opcodes.add(6)
        }
        if (definition.ramps7 != null) {
            opcodes.add(7)
        }
        if (definition.ramps23 != null) {
            opcodes.add(23)
        }
        if (definition.unknown8) {
            opcodes.add(8)
        }
        if (definition.unknown9 != -1) {
            opcodes.add(9)
        }
        if (definition.unknown10) {
            opcodes.add(10)
        }
        if (definition.unknown11 != -1) {
            opcodes.add(11)
        }
        if (definition.unknown12First != -1) {
            opcodes.add(12)
        }
        if (definition.unknown13 != -1) {
            opcodes.add(13)
        }
        if (definition.unknown14 != -1) {
            opcodes.add(14)
        }
        if (definition.unknown15 != -1) {
            opcodes.add(15)
        }
        if (definition.unknown16) {
            opcodes.add(16)
        }
        if (definition.unknown17 != null) {
            opcodes.add(17)
        }
        if (definition.unknown18 != null) {
            opcodes.add(18)
        }
        if (definition.unknown19 != null) {
            opcodes.add(19)
        }
        if (definition.unknown20First != -1) {
            opcodes.add(20)
        }
        if (definition.unknown21 != -1) {
            opcodes.add(21)
        }
        if (definition.unknown22Head != null) {
            opcodes.add(22)
        }
        if (definition.unknown24 != null) {
            opcodes.add(24)
        }
        if (definition.unknown25 != null) {
            opcodes.add(25)
        }
        if (definition.unknown26 != null) {
            opcodes.add(26)
        }
        if (definition.unknown27 != null) {
            opcodes.add(27)
        }
        if (definition.unknown28 != null) {
            opcodes.add(28)
        }
        if (definition.unknown29First != null) {
            opcodes.add(29)
        }
        return opcodes.toIntArray()
    }

    private fun Writer.writeRamps(ramps: Array<DefaultsRamp>?) {
        val cells = ramps ?: return
        for (cell in cells) {
            writeShort(cell.value)
            writeShort(cell.ids.size)
            for (id in cell.ids) {
                writeShort(id)
            }
        }
    }

    override fun Writer.encodeOpcode(definition: DefaultsGroup3Type, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> for (pair in definition.pairs!!) {
                writeShort(pair.first)
                writeShort(pair.second)
            }
            2 -> writeBigSmart(definition.unknown2!!)
            3 -> writeByte(definition.pairCount)
            4, 8, 10, 16 -> Unit
            5 -> writeMedium(definition.unknown5)
            6 -> writeMedium(definition.unknown6)
            7 -> writeRamps(definition.ramps7)
            9 -> writeByte(definition.unknown9)
            11 -> writeByte(definition.unknown11)
            12 -> {
                writeShort(definition.unknown12First)
                writeShort(definition.unknown12Second)
            }
            13 -> writeByte(definition.unknown13)
            14 -> writeByte(definition.unknown14)
            15 -> writeByte(definition.unknown15)
            17 -> writeInt(definition.unknown17!!)
            18 -> writeInt(definition.unknown18!!)
            19 -> writeInt(definition.unknown19!!)
            20 -> {
                writeShort(definition.unknown20First)
                writeByte(definition.unknown20Second)
            }
            21 -> writeByte(definition.unknown21)
            22 -> {
                for (id in definition.unknown22Head!!) {
                    writeBigSmart(id)
                }
                writeByte(definition.unknown22FirstOffset)
                writeByte(definition.unknown22SecondOffset)
                for (id in definition.unknown22Tail!!) {
                    writeBigSmart(id)
                }
            }
            23 -> writeRamps(definition.ramps23)
            24 -> writeInt(definition.unknown24!!)
            25 -> for (id in definition.unknown25!!) {
                writeBigSmart(id)
            }
            26 -> writeInt(definition.unknown26!!)
            27 -> writeInt(definition.unknown27!!)
            28 -> writeInt(definition.unknown28!!)
            29 -> {
                writeInt(definition.unknown29First!!)
                writeInt(definition.unknown29Second!!)
            }
            else -> error("Unhandled defaults group 3 opcode $opcode in ${definition.id}")
        }
    }
}

class DefaultsGroup4Encoder : OpcodeEncoder<DefaultsGroup4Type>() {
    override fun opcodes(definition: DefaultsGroup4Type) =
        if (definition.unknown1 == null) IntArray(0) else intArrayOf(1)

    override fun Writer.encodeOpcode(definition: DefaultsGroup4Type, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeInt(definition.unknown1!!)
            else -> error("Unhandled defaults group 4 opcode $opcode in ${definition.id}")
        }
    }
}

class DefaultsGroup5Encoder : OpcodeEncoder<DefaultsGroup5Type>() {
    override fun opcodes(definition: DefaultsGroup5Type) = IntArray(0)
    override fun Writer.encodeOpcode(definition: DefaultsGroup5Type, opcode: Int, occurrence: Int) =
        error("Unhandled defaults group 5 opcode $opcode in ${definition.id}")
}

class DefaultsGroup6Encoder : OpcodeEncoder<DefaultsGroup6Type>() {
    override fun opcodes(definition: DefaultsGroup6Type): IntArray {
        val opcodes = IntArrayList()
        if (definition.slots != null) {
            opcodes.add(1)
        }
        if (definition.unknown3 != -1) {
            opcodes.add(3)
        }
        if (definition.unknown5 != null) {
            opcodes.add(5)
        }
        if (definition.unknown4 != -1) {
            opcodes.add(4)
        }
        if (definition.unknown6 != null) {
            opcodes.add(6)
        }
        if (definition.unknown7 != null) {
            opcodes.add(7)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: DefaultsGroup6Type, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeCountedBytes(definition.slots)
            3 -> writeByte(definition.unknown3)
            4 -> writeByte(definition.unknown4)
            5 -> writeCountedBytes(definition.unknown5)
            6 -> writeCountedBytes(definition.unknown6)
            7 -> for (value in definition.unknown7!!) {
                writeByte(value)
            }
            else -> error("Unhandled defaults group 6 opcode $opcode in ${definition.id}")
        }
    }
}

class DefaultsGroup7Encoder : OpcodeEncoder<DefaultsGroup7Type>() {
    override fun opcodes(definition: DefaultsGroup7Type): IntArray {
        val opcodes = IntArrayList()
        if (definition.light1 != null) {
            opcodes.add(1)
        }
        if (definition.light2 != null) {
            opcodes.add(2)
        }
        if (definition.light3 != null) {
            opcodes.add(3)
        }
        if (definition.light4 != null) {
            opcodes.add(4)
        }
        if (definition.ids5 != null) {
            opcodes.add(5)
        }
        if (definition.ids6 != null) {
            opcodes.add(6)
        }
        if (definition.ids7 != null) {
            opcodes.add(7)
        }
        if (definition.discarded8 != null) {
            opcodes.add(8)
        }
        if (definition.discarded9 != null) {
            opcodes.add(9)
        }
        if (definition.discarded10 != null) {
            opcodes.add(10)
        }
        if (definition.unknown11) {
            opcodes.add(11)
        }
        if (definition.unknown12 != null) {
            opcodes.add(12)
        }
        if (definition.unknown13 != null) {
            opcodes.add(13)
        }
        return opcodes.toIntArray()
    }

    private fun Writer.writeLight(light: DefaultsLight) {
        writeByte(light.kind)
        when (light.kind) {
            0 -> {
                writeByte(light.first)
                writeByte(light.second)
                writeCountedBytes(light.ids)
            }
            1 -> {
                writeByte(light.first)
                writeByte(light.second)
            }
            2 -> writeCountedBytes(light.ids)
        }
    }

    override fun Writer.encodeOpcode(definition: DefaultsGroup7Type, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeLight(definition.light1!!)
            2 -> writeLight(definition.light2!!)
            3 -> writeLight(definition.light3!!)
            4 -> writeLight(definition.light4!!)
            5 -> writeCountedBytes(definition.ids5)
            6 -> writeCountedBytes(definition.ids6)
            7 -> writeCountedBytes(definition.ids7)
            8 -> writeLight(definition.discarded8!!)
            9 -> writeLight(definition.discarded9!!)
            10 -> writeLight(definition.discarded10!!)
            11 -> Unit
            12 -> writeInt(definition.unknown12!!)
            13 -> writeInt(definition.unknown13!!)
            else -> error("Unhandled defaults group 7 opcode $opcode in ${definition.id}")
        }
    }
}

class DefaultsGroup8Encoder : OpcodeEncoder<DefaultsGroup8Type>() {
    override fun opcodes(definition: DefaultsGroup8Type): IntArray {
        val opcodes = IntArrayList()
        if (definition.unknown1 != -1) {
            opcodes.add(1)
        }
        if (definition.unknown13 != -1) {
            opcodes.add(13)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: DefaultsGroup8Type, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeByte(definition.unknown1)
            13 -> writeByte(definition.unknown13)
            else -> error("Unhandled defaults group 8 opcode $opcode in ${definition.id}")
        }
    }
}

class DefaultsGroup9Encoder : OpcodeEncoder<DefaultsGroup9Type>() {
    override fun opcodes(definition: DefaultsGroup9Type): IntArray {
        val opcodes = IntArrayList()
        if (definition.curveCount != -1) {
            opcodes.add(2)
        }
        if (definition.skills != null) {
            opcodes.add(1)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: DefaultsGroup9Type, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                val skills = definition.skills!!
                writeByte(skills.size)
                for (skill in skills) {
                    writeByte(skill.skill)
                    writeShort(skill.levelCap)
                    writeByte(skill.flags)
                    if (skill.flags and 2 != 0) {
                        writeByte(skill.softCap)
                    }
                    if (skill.flags and 4 != 0) {
                        writeByte(skill.curve)
                    }
                    if (skill.flags and 8 != 0) {
                        writeByte(skill.levelOffset)
                    }
                    writeByte(skill.trailing)
                }
            }
            2 -> {
                writeByte(definition.curveCount)
                for (curve in definition.curves ?: emptyArray()) {
                    writeByte(curve.index)
                    writeShort(curve.experience.size)
                    for (value in curve.experience) {
                        writeInt(value)
                    }
                }
                writeByte(DefaultsGroup9Decoder.CURVE_TERMINATOR)
            }
            else -> error("Unhandled defaults group 9 opcode $opcode in ${definition.id}")
        }
    }
}

class DefaultsGroup10Encoder : OpcodeEncoder<DefaultsGroup10Type>() {
    override fun opcodes(definition: DefaultsGroup10Type): IntArray {
        val opcodes = IntArrayList()
        if (definition.unknown1 != null) {
            opcodes.add(1)
        }
        if (definition.colour2 != null) {
            opcodes.add(2)
        }
        if (definition.colour3 != null) {
            opcodes.add(3)
        }
        if (definition.unknown4 != -1) {
            opcodes.add(4)
        }
        if (definition.unknown5 != -1) {
            opcodes.add(5)
        }
        if (definition.unknown6 != null) {
            opcodes.add(6)
        }
        if (definition.colour7 != null) {
            opcodes.add(7)
        }
        val cells = definition.grid
        if (cells != null) {
            for (cell in cells.indices) {
                if (cells[cell] != -1) {
                    opcodes.add(gridOpcode(cell))
                }
            }
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: DefaultsGroup10Type, opcode: Int, occurrence: Int) {
        when {
            opcode == 1 -> writeInt(definition.unknown1!!)
            opcode == 2 -> writeInt(definition.colour2!!)
            opcode == 3 -> writeInt(definition.colour3!!)
            opcode == 4 -> writeByte(definition.unknown4)
            opcode == 5 -> writeByte(definition.unknown5)
            opcode == 6 -> writeInt(definition.unknown6!!)
            opcode == 7 -> writeInt(definition.colour7!!)
            opcode >= DefaultsGroup10Decoder.GRID_BASE -> {
                val offset = opcode - DefaultsGroup10Decoder.GRID_BASE
                writeShort(definition.grid!![(offset shr 3) + (offset and 7) * DefaultsGroup10Decoder.GRID_ROWS])
            }
            else -> error("Unhandled defaults group 10 opcode $opcode in ${definition.id}")
        }
    }

    private fun gridOpcode(cell: Int) = DefaultsGroup10Decoder.GRID_BASE +
        (cell % DefaultsGroup10Decoder.GRID_ROWS) * 8 + cell / DefaultsGroup10Decoder.GRID_ROWS
}

class DefaultsGroup12Encoder : OpcodeEncoder<DefaultsGroup12Type>() {
    override fun opcodes(definition: DefaultsGroup12Type): IntArray {
        val opcodes = IntArrayList()
        if (definition.unknown1First != null) {
            opcodes.add(1)
        }
        if (definition.unknown2 != null) {
            opcodes.add(2)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: DefaultsGroup12Type, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                writeBigSmart(definition.unknown1First!!)
                writeBigSmart(definition.unknown1Second!!)
            }
            2 -> {
                val values = definition.unknown2!!
                writeByte(values.size)
                for (value in values) {
                    writeShort(value)
                }
            }
            else -> error("Unhandled defaults group 12 opcode $opcode in ${definition.id}")
        }
    }
}
