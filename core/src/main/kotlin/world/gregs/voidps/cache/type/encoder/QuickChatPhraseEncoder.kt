package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.QuickChatPhraseType

class QuickChatPhraseEncoder : OpcodeEncoder<QuickChatPhraseType>() {

    override fun opcodes(definition: QuickChatPhraseType): IntArray {
        val opcodes = IntArrayList()
        if (!definition.flag) {
            opcodes.add(4)
        }
        if (definition.stringParts != null) {
            opcodes.add(1)
        }
        if (definition.responses != null) {
            opcodes.add(2)
        }
        if (definition.types != null) {
            opcodes.add(5)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: QuickChatPhraseType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeString(definition.stringParts?.joinToString("<"))
            2 -> {
                val responses = definition.responses ?: IntArray(0)
                writeByte(responses.size)
                for (response in responses) {
                    writeShort(response)
                }
            }
            3, 5 -> {
                val types = definition.types ?: IntArray(0)
                val ids = definition.ids ?: Array(types.size) { IntArray(0) }
                writeByte(types.size)
                for (param in types.indices) {
                    writeShort(types[param])
                    for (id in ids[param]) {
                        if (opcode == 5) writeVarInt(id) else writeShort(id)
                    }
                }
            }
            4 -> Unit
            else -> error("Unhandled quick chat phrase opcode $opcode in ${definition.id}")
        }
    }
}
