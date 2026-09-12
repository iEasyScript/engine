package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.QuickChatCatType

class QuickChatCatEncoder : OpcodeEncoder<QuickChatCatType>() {

    override fun opcodes(definition: QuickChatCatType): IntArray {
        val opcodes = IntArrayList()
        if (definition.title != null) {
            opcodes.add(1)
        }
        if (definition.subCategoryIds != null) {
            opcodes.add(2)
        }
        if (definition.phraseIds != null) {
            opcodes.add(3)
        }
        if (definition.flag) {
            opcodes.add(4)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: QuickChatCatType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeString(definition.title)
            2 -> writeEntries(definition.subCategoryIds, definition.subCategoryKeys)
            3 -> writeEntries(definition.phraseIds, definition.phraseKeys)
            4 -> Unit
            else -> error("Unhandled quick chat category opcode $opcode in ${definition.id}")
        }
    }

    private fun Writer.writeEntries(ids: IntArray?, keys: CharArray?) {
        val entries = ids ?: IntArray(0)
        val hotkeys = keys ?: CharArray(entries.size)
        writeByte(entries.size)
        for (entry in entries.indices) {
            writeShort(entries[entry])
            writeTypeChar(hotkeys[entry])
        }
    }
}
