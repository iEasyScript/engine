package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.StructType

class StructEncoder : OpcodeEncoder<StructType>() {

    override fun opcodes(definition: StructType): IntArray =
        if (definition.params == null) IntArray(0) else intArrayOf(PARAMETERS)

    override fun Writer.encodeOpcode(definition: StructType, opcode: Int, occurrence: Int) {
        when (opcode) {
            PARAMETERS -> writeParams(definition)
            else -> error("Unhandled struct opcode $opcode in ${definition.id}")
        }
    }

    private companion object {
        const val PARAMETERS = 249
    }
}
