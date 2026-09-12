package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.EnumEntry
import world.gregs.voidps.cache.type.data.EnumType

/**
 * Writes an index 17 enum file.
 *
 * Four opcodes spell the same entry list: 5 and 6 key it by an int, 7 and 8 by an index into an array
 * whose capacity leads the record, and the odd one of each pair holds strings.
 */
class EnumEncoder : OpcodeEncoder<EnumType>() {

    override fun opcodes(definition: EnumType): IntArray {
        val opcodes = IntArrayList()
        if (definition.keyType.code != 0) {
            opcodes.add(1)
        }
        if (definition.valueType.code != 0) {
            opcodes.add(2)
        }
        if (definition.defaultString != "null") {
            opcodes.add(3)
        }
        if (definition.defaultInt != 0) {
            opcodes.add(4)
        }
        if (definition.map != null) {
            val packed = definition.arraySize != 0
            val strings = strings(definition)
            opcodes.add(if (packed) (if (strings) 7 else 8) else (if (strings) 5 else 6))
        }
        if (definition.keyTypeId != -1) {
            opcodes.add(101)
        }
        if (definition.valueTypeId != -1) {
            opcodes.add(102)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: EnumType, opcode: Int, occurrence: Int) {
        when (opcode) {
            1 -> writeTypeChar(definition.keyType)
            2 -> writeTypeChar(definition.valueType)
            3 -> writeString(definition.defaultString)
            4 -> writeInt(definition.defaultInt)
            5, 6 -> writeEntries(definition, opcode == 5) { writeInt(it) }
            7, 8 -> {
                writeShort(definition.arraySize)
                writeEntries(definition, opcode == 7) { writeShort(it) }
            }
            101 -> writeSmart(definition.keyTypeId)
            102 -> writeSmart(definition.valueTypeId)
            else -> error("Unhandled enum opcode $opcode in ${definition.id}")
        }
    }

    private fun Writer.writeEntries(definition: EnumType, strings: Boolean, key: (Int) -> Unit) {
        val entries = entries(definition)
        writeShort(entries.size)
        for (entry in entries) {
            key(entry.key)
            if (strings) {
                writeString(entry.value as String)
            } else {
                writeInt(entry.value as Int)
            }
        }
    }

    private fun entries(definition: EnumType): List<EnumEntry> =
        definition.entries ?: definition.map?.map { EnumEntry(it.key, it.value) } ?: emptyList()

    private fun strings(definition: EnumType): Boolean = entries(definition).firstOrNull()?.value is String
}
