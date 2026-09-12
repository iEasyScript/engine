package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.EffectAnimType

class EffectAnimEncoder : OpcodeEncoder<EffectAnimType>() {

    override fun opcodes(definition: EffectAnimType): IntArray {
        val opcodes = IntArrayList()
        with(definition) {
            if (mode != null) opcodes.add(1)
            if (angle != null) opcodes.add(2)
            if (magnitude != null) opcodes.add(3)
            if (signedMagnitude != null) opcodes.add(4)
            if (floatA != null) opcodes.add(5)
            if (floatB != null) opcodes.add(6)
            if (floatC != null) opcodes.add(7)
            if (floatD != null) opcodes.add(8)
            if (flag != null) opcodes.add(9)
            if (valueA != null) opcodes.add(10)
            if (valueB != null) opcodes.add(11)
            if (floatE != null) opcodes.add(12)
            if (floatF != null) opcodes.add(13)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: EffectAnimType, opcode: Int, occurrence: Int) {
        with(definition) {
            when (opcode) {
                1 -> writeByte(mode!!)
                2 -> writeShort(angle!!)
                3 -> writeShort(magnitude!!)
                4 -> writeShort(signedMagnitude!!)
                5 -> writeFloat(floatA!!)
                6 -> writeFloat(floatB!!)
                7 -> writeFloat(floatC!!)
                8 -> writeFloat(floatD!!)
                9 -> Unit
                10 -> writeInt(valueA!!)
                11 -> writeInt(valueB!!)
                12 -> writeFloat(floatE!!)
                13 -> writeFloat(floatF!!)
            }
        }
    }
}
