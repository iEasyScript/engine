package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Config.DBTABLE
import world.gregs.voidps.cache.type.ConfigDecoder
import world.gregs.voidps.cache.type.data.DbColumn
import world.gregs.voidps.cache.type.data.DbTableType
import world.gregs.voidps.cache.type.encoder.DbTableEncoder

/**
 * Decodes a database table: the tuple layout each column repeats, plus any table-level defaults.
 * The row side ([DbRowDecoder]) carries the values; this is the schema those values conform to.
 */
class DbTableDecoder : ConfigDecoder<DbTableType>(DBTABLE) {
    override fun create(size: Int) = Array(size) { DbTableType(it) }

    private val encoder = DbTableEncoder()

    override fun canonicalOpcodes(definition: DbTableType): IntArray = encoder.opcodes(definition)

    override fun DbTableType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            COLUMNS -> readColumns(buffer)
            else -> unknown(opcode, buffer)
        }
    }

    private fun DbTableType.readColumns(buffer: Reader) {
        header = buffer.readInt()
        columnCount = buffer.readUnsignedByte()
        val columns = LinkedHashMap<Int, DbColumn>(columnCount)
        while (true) {
            val index = buffer.readUnsignedByte()
            if (index == END_OF_COLUMNS) {
                this.columns = columns
                return
            }
            val flags = buffer.readUnsignedByte()
            val types = IntArray(buffer.readUnsignedByte()) { buffer.readSmart() }
            columns[index] = DbColumn(index, types, readDefault(buffer, types), flags)
        }
    }

    private fun DbTableType.readDefault(buffer: Reader, types: IntArray): List<Any> {
        val values = mutableListOf<Any>()
        while (true) {
            when (val field = buffer.readUnsignedByte()) {
                END_OF_COLUMN -> return values
                DEFAULT -> repeat(buffer.readSmart()) {
                    for (type in types) {
                        values.add(if (type == STRING) buffer.readString() else buffer.readInt())
                    }
                }
                else -> error("Dbtable $id column has unknown field $field at ${buffer.position()}")
            }
        }
    }

    private companion object {
        const val COLUMNS = 2
        const val DEFAULT = 2
        const val END_OF_COLUMN = 0
        const val END_OF_COLUMNS = 0xff
        const val STRING = 36
    }
}
