package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.HitmarkType

class HitmarkEncoder : OpcodeEncoder<HitmarkType>() {

    override fun opcodes(definition: HitmarkType): IntArray {
        val opcodes = IntArrayList()
        if (definition.field1 != -1) {
            opcodes.add(1)
        }
        if (definition.hasColour) {
            opcodes.add(2)
        }
        if (definition.field3 != -1) {
            opcodes.add(3)
        }
        if (definition.field4 != -1) {
            opcodes.add(4)
        }
        if (definition.field5 != -1) {
            opcodes.add(5)
        }
        if (definition.field6 != -1) {
            opcodes.add(6)
        }
        if (definition.field7 != 0) {
            opcodes.add(7)
        }
        if (definition.text.isNotEmpty()) {
            opcodes.add(8)
        }
        if (definition.displayDuration != 70) {
            opcodes.add(9)
        }
        if (definition.field10 != 0) {
            opcodes.add(10)
        }
        if (definition.replacementValue != -1) {
            opcodes.add(12)
        }
        if (definition.field13 != 0) {
            opcodes.add(13)
        }
        if (definition.field14 != -1) {
            opcodes.add(14)
        }
        if (definition.field16a != 0 || definition.field16b != 0) {
            opcodes.add(16)
        }
        if (definition.transforms != null) {
            opcodes.add(if (definition.transforms!!.last() != -1) 22 else 21)
        }
        if (definition.field19 != 1) {
            opcodes.add(19)
        }
        if (definition.field20 != 1) {
            opcodes.add(20)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: HitmarkType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeBigSmart(definition.field1)
            2 -> writeMedium(definition.colour)
            3 -> writeBigSmart(definition.field3)
            4 -> writeBigSmart(definition.field4)
            5 -> writeBigSmart(definition.field5)
            6 -> writeBigSmart(definition.field6)
            7 -> writeShort(definition.field7)
            8 -> writeVersionedString(definition.text)
            9 -> writeShort(definition.displayDuration)
            10 -> writeShort(definition.field10)
            11, 15 -> Unit
            12 -> writeByte(definition.replacementValue)
            13 -> writeShort(definition.field13)
            14 -> writeShort(definition.field14)
            16 -> {
                writeShort(definition.field16a)
                writeShort(definition.field16b)
            }
            17, 18 -> writeShortTransforms(definition, opcode == 18, wideVarbit = false)
            21, 22 -> writeShortTransforms(definition, opcode == 22, wideVarbit = true)
            19 -> writeShort(definition.field19)
            20 -> writeShort(definition.field20)
            else -> error("Unhandled hitmark opcode $opcode in ${definition.id}")
        }
    }
}
