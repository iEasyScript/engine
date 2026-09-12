package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.DBROW
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.DbColumnValues
import world.gregs.voidps.cache.type.data.DbRowType
import world.gregs.voidps.cache.type.encoder.DbRowEncoder

/**
 * Decodes a database row: the table it belongs to plus every populated column. Rows are the
 * value side of the dbtable system - [DbTableIndexDecoder] enumerates which row ids a table
 * owns, this reads what those rows actually hold.
 */
class DbRowDecoder : ConfigDecoder<DbRowType>(DBROW) {
    override fun create(size: Int) = Array(size) { DbRowType(it) }

    private val encoder = DbRowEncoder()

    override fun canonicalOpcodes(definition: DbRowType): IntArray = encoder.opcodes(definition)

    override fun DbRowType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            COLUMNS -> columns = readColumns(buffer)
            TABLE -> table = buffer.readVarInt()
            else -> unknown(opcode, buffer)
        }
    }

    private fun DbRowType.readColumns(buffer: Reader): Map<Int, DbColumnValues> {
        columnCount = buffer.readSmart()
        val columns = LinkedHashMap<Int, DbColumnValues>(columnCount)
        while (true) {
            val index = buffer.readUnsignedByte()
            if (index == END_OF_COLUMNS) {
                return columns
            }
            val types = IntArray(buffer.readUnsignedByte()) { buffer.readSmart() }
            val tuples = buffer.readSmart()
            val values = ArrayList<Any>(tuples * types.size)
            repeat(tuples) {
                for (type in types) {
                    values.add(readValue(buffer, type))
                }
            }
            columns[index] = DbColumnValues(types, values)
        }
    }

    private fun DbRowType.readValue(buffer: Reader, type: Int): Any = when (type) {
        STRING -> buffer.readString()
        in INTEGER_TYPES -> buffer.readInt()
        else -> error("Dbrow $id column value has unknown type $type at ${buffer.position()}")
    }

    private companion object {
        const val COLUMNS = 3
        const val TABLE = 4
        const val END_OF_COLUMNS = 0xff
        const val STRING = 36

        /** Every non-string type present in the live cache; all encode as a big-endian int. */
        val INTEGER_TYPES = setOf(
            0, 1, 3, 5, 6, 8, 9, 17, 22, 23, 25, 26, 28, 30, 31, 32, 33, 39, 41,
            44, 57, 73, 74, 97, 99, 102, 131, 133, 209, 210,
        )
    }
}
