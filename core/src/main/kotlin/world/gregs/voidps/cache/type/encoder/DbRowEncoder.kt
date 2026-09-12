package world.gregs.voidps.cache.type.encoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.DbRowType

class DbRowEncoder : OpcodeEncoder<DbRowType>() {

    override fun opcodes(definition: DbRowType): IntArray {
        val opcodes = IntArrayList()
        if (definition.columns.isNotEmpty()) {
            opcodes.add(COLUMNS)
        }
        if (definition.table != -1) {
            opcodes.add(TABLE)
        }
        return opcodes.toIntArray()
    }

    override fun Writer.encodeOpcode(definition: DbRowType, opcode: Int, occurrence: Int) {
        when (opcode) {
            COLUMNS -> {
                writeSmart(definition.columnCount)
                for ((index, column) in definition.columns) {
                    writeByte(index)
                    writeByte(column.types.size)
                    for (type in column.types) {
                        writeSmart(type)
                    }
                    writeSmart(column.tupleCount)
                    for (value in column.values) {
                        if (value is String) {
                            writeString(value)
                        } else {
                            writeInt(value as Int)
                        }
                    }
                }
                writeByte(END_OF_COLUMNS)
            }
            TABLE -> writeVarInt(definition.table)
            else -> error("Unhandled dbrow opcode $opcode in ${definition.id}")
        }
    }

    private companion object {
        const val COLUMNS = 3
        const val TABLE = 4
        const val END_OF_COLUMNS = 0xff
    }
}
