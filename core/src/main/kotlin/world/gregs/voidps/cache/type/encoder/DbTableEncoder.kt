package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.type.data.DbColumn
import world.gregs.voidps.cache.type.data.DbTableType

/** Writes an index 2 archive 40 table: the column list, closed by a sentinel index rather than a count. */
class DbTableEncoder : OpcodeEncoder<DbTableType>() {

    override fun opcodes(definition: DbTableType): IntArray =
        if (definition.columns.isEmpty()) IntArray(0) else intArrayOf(COLUMNS)

    override fun Writer.encodeOpcode(definition: DbTableType, opcode: Int, occurrence: Int) {
        when (opcode) {
            COLUMNS -> {
                writeInt(definition.header)
                writeByte(definition.columnCount)
                for (column in definition.columns.values) {
                    writeColumn(column)
                }
                writeByte(END_OF_COLUMNS)
            }
            else -> error("Unhandled dbtable opcode $opcode in ${definition.id}")
        }
    }

    private fun Writer.writeColumn(column: DbColumn) {
        writeByte(column.index)
        writeByte(column.flags)
        writeByte(column.types.size)
        for (type in column.types) {
            writeSmart(type)
        }
        if (column.default.isNotEmpty()) {
            writeByte(DEFAULT)
            writeSmart(column.default.size / column.types.size)
            for (value in column.default) {
                if (value is String) {
                    writeString(value)
                } else {
                    writeInt(value as Int)
                }
            }
        }
        writeByte(END_OF_COLUMN)
    }

    private companion object {
        const val COLUMNS = 2
        const val DEFAULT = 2
        const val END_OF_COLUMN = 0
        const val END_OF_COLUMNS = 0xff
    }
}
