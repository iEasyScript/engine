package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.ParticleProducerType

class ParticleProducerEncoder : OpcodeEncoder<ParticleProducerType>() {

    override fun opcodes(definition: ParticleProducerType): IntArray {
        val opcodes = IntArrayList()
        for (opcode in ORDER) {
            if (present(definition, opcode)) {
                opcodes.add(opcode)
            }
        }
        return opcodes.toIntArray()
    }

    private fun present(definition: ParticleProducerType, opcode: Int): Boolean = when (opcode) {
        1 -> definition.unknown1a != null
        3 -> definition.unknown3a != null
        4 -> definition.unknown4a != null
        5 -> definition.unknown5 != null
        6 -> definition.unknown6a != null
        7 -> definition.unknown7a != null
        8 -> definition.unknown8a != null
        9 -> definition.unknown9 != null
        10 -> definition.unknown10 != null
        12 -> definition.unknown12 != null
        13 -> definition.unknown13 != null
        14 -> definition.unknown14 != null
        15 -> definition.unknown15 != null
        16 -> definition.unknown16a != null
        18 -> definition.unknown18 != null
        19 -> definition.unknown19 != null
        20 -> definition.unknown20 != null
        21 -> definition.unknown21 != null
        22 -> definition.unknown22 != null
        23 -> definition.unknown23 != null
        24 -> definition.unknown24 != null
        25 -> definition.unknown25 != null
        26 -> definition.unknown26
        27 -> definition.unknown27 != null
        28 -> definition.unknown28 != null
        29 -> definition.unknown29 != null
        30 -> definition.unknown30
        31 -> definition.unknown31a != null
        32 -> definition.unknown32
        33 -> definition.unknown33
        34 -> definition.unknown34
        35 -> definition.unknown35 != null
        36 -> definition.unknown36
        else -> false
    }

    override fun Writer.encodeOpcode(definition: ParticleProducerType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> {
                writeShort(definition.unknown1a ?: 0)
                writeShort(definition.unknown1b ?: 0)
                writeShort(definition.unknown1c ?: 0)
                writeShort(definition.unknown1d ?: 0)
            }
            3 -> {
                writeInt(definition.unknown3a ?: 0)
                writeInt(definition.unknown3b ?: 0)
            }
            4 -> {
                writeByte(definition.unknown4a ?: 0)
                writeByte(definition.unknown4b ?: 0)
            }
            5 -> writeShort(definition.unknown5 ?: 0)
            6 -> {
                writeInt(definition.unknown6a ?: 0)
                writeInt(definition.unknown6b ?: 0)
            }
            7 -> {
                writeShort(definition.unknown7a ?: 0)
                writeShort(definition.unknown7b ?: 0)
            }
            8 -> {
                writeShort(definition.unknown8a ?: 0)
                writeShort(definition.unknown8b ?: 0)
            }
            9 -> writeShortList(definition.unknown9)
            10 -> writeShortList(definition.unknown10)
            12 -> writeByte(definition.unknown12 ?: 0)
            13 -> writeByte(definition.unknown13 ?: 0)
            14 -> writeShort(definition.unknown14 ?: 0)
            15 -> writeShort(definition.unknown15 ?: 0)
            16 -> {
                writeByte(definition.unknown16a ?: 0)
                writeShort(definition.unknown16b ?: 0)
                writeShort(definition.unknown16c ?: 0)
                writeByte(definition.unknown16d ?: 0)
            }
            18 -> writeInt(definition.unknown18 ?: 0)
            19 -> writeByte(definition.unknown19 ?: 0)
            20 -> writeByte(definition.unknown20 ?: 0)
            21 -> writeByte(definition.unknown21 ?: 0)
            22 -> writeInt(definition.unknown22 ?: 0)
            23 -> writeByte(definition.unknown23 ?: 0)
            24 -> writeByte(definition.unknown24 ?: 0)
            25 -> writeShortList(definition.unknown25)
            27 -> writeShort(definition.unknown27 ?: 0)
            28 -> writeByte(definition.unknown28 ?: 0)
            29 -> writeBytes(definition.unknown29 ?: ByteArray(3))
            31 -> {
                writeShort(definition.unknown31a ?: 0)
                writeShort(definition.unknown31b ?: 0)
            }
            35 -> writeBytes(definition.unknown35 ?: ByteArray(3))
            26, 30, 32, 33, 34, 36 -> Unit
            else -> error("Unhandled particle producer opcode $opcode in ${definition.id}")
        }
    }

    private fun Writer.writeShortList(values: IntArray?) {
        val list = values ?: IntArray(0)
        writeByte(list.size)
        for (value in list) {
            writeShort(value)
        }
    }

    private companion object {
        val ORDER = intArrayOf(1, 3, 4, 5, 31, 27, 28, 7, 8, 9, 10, 25, 12, 13, 14, 15, 16, 19, 20, 24, 30, 32, 33, 34, 36, 26, 21, 22, 23, 29, 35, 6, 18)
    }
}
