package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.ParticleType

class ParticleEncoder : OpcodeEncoder<ParticleType>() {

    override fun opcodes(definition: ParticleType): IntArray {
        val opcodes = IntArrayList()
        if (definition.unknown1 != null) {
            opcodes.add(1)
        }
        if (definition.unknown4Type != null || definition.unknown4Value != null) {
            opcodes.add(4)
        }
        if (definition.unknown6) {
            opcodes.add(6)
        }
        if (definition.unknown2) {
            opcodes.add(2)
        }
        if (definition.unknown8) {
            opcodes.add(8)
        }
        if (definition.unknown9) {
            opcodes.add(9)
        }
        if (definition.unknown10) {
            opcodes.add(10)
        }
        if (definition.vector != null) {
            opcodes.add(3)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: ParticleType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeShort(definition.unknown1 ?: 0)
            2 -> Unit
            3 -> {
                val vector = definition.vector
                writeInt(vector?.x ?: 0)
                writeInt(vector?.y ?: 0)
                writeInt(vector?.z ?: 0)
            }
            4 -> {
                writeByte(definition.unknown4Type ?: 0)
                writeInt(definition.unknown4Value ?: 0)
            }
            6, 8, 9, 10 -> Unit
            else -> error("Unhandled particle opcode $opcode in ${definition.id}")
        }
    }
}
