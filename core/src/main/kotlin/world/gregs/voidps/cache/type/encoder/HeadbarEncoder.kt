package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.HeadbarType

class HeadbarEncoder : OpcodeEncoder<HeadbarType>() {

    override fun opcodes(definition: HeadbarType): IntArray {
        val opcodes = IntArrayList()
        if (definition.sortKey != 255) {
            opcodes.add(2)
        }
        if (definition.field3 != 255) {
            opcodes.add(3)
        }
        if (definition.fadeStart == 0) {
            opcodes.add(4)
        }
        if (definition.fadeEnd != 70) {
            opcodes.add(5)
        }
        if (definition.sizeClass0Fill != -1) {
            opcodes.add(7)
        }
        if (definition.sizeClass0Frame != -1) {
            opcodes.add(8)
        }
        if (definition.sizeClass1Fill != -1) {
            opcodes.add(9)
        }
        if (definition.sizeClass1Frame != -1) {
            opcodes.add(10)
        }
        if (definition.fadeStartDelay != -1) {
            opcodes.add(11)
        }
        if (definition.sizeClass2Fill != -1) {
            opcodes.add(12)
        }
        if (definition.sizeClass2Frame != -1) {
            opcodes.add(13)
        }
        if (definition.centredOverlay != -1) {
            opcodes.add(14)
        }
        if (definition.fillEdgeCap != -1) {
            opcodes.add(15)
        }
        if (definition.useAlternateBlitter) {
            opcodes.add(16)
        }
        if (definition.barHeightPx != 2) {
            opcodes.add(17)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: HeadbarType, opcode: Int, occurrence: Int) {
        when (opcode) {
            2 -> writeByte(definition.sortKey)
            3 -> writeByte(definition.field3)
            4 -> Unit
            5 -> writeShort(definition.fadeEnd)
            7 -> writeBigSmart(definition.sizeClass0Fill)
            8 -> writeBigSmart(definition.sizeClass0Frame)
            9 -> writeBigSmart(definition.sizeClass1Fill)
            10 -> writeBigSmart(definition.sizeClass1Frame)
            11 -> writeShort(definition.fadeStartDelay)
            12 -> writeBigSmart(definition.sizeClass2Fill)
            13 -> writeBigSmart(definition.sizeClass2Frame)
            14 -> writeBigSmart(definition.centredOverlay)
            15 -> writeBigSmart(definition.fillEdgeCap)
            16 -> Unit
            17 -> writeByte(definition.barHeightPx)
            else -> error("Unhandled headbar opcode $opcode in ${definition.id}")
        }
    }
}
