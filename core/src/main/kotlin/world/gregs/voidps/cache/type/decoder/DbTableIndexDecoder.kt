package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index.DBTABLEINDEX
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.DbColumnIndexType
import world.gregs.voidps.cache.type.data.DbIndexField
import world.gregs.voidps.cache.type.data.DbIndexRangeValue
import world.gregs.voidps.cache.type.data.DbIndexRanges
import world.gregs.voidps.cache.type.data.DbIndexValue
import world.gregs.voidps.cache.type.data.DbTableIndexType

/**
 * Decodes one column's index. A group is a dbtable id and every file in it is the index for the
 * column whose id the file carries, so a definition id packs the two.
 */
class DbColumnIndexDecoder : TypeDecoder<DbColumnIndexType>(DBTABLEINDEX) {

    override fun create(size: Int) = Array(size) { DbColumnIndexType(it) }

    override fun getArchive(id: Int) = id shr COLUMN_BITS

    override fun getFile(id: Int) = id and COLUMN_MASK

    override fun readLoop(definition: DbColumnIndexType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun DbColumnIndexType.read(opcode: Int, buffer: Reader) = Unit

    private fun DbColumnIndexType.decode(buffer: Reader) {
        if (buffer.readUnsignedByte() == VERSION_MARKER) {
            version = buffer.readUnsignedByte()
        } else {
            buffer.position(buffer.position() - 1)
        }
        fields = List(buffer.readVarInt()) { readField(buffer) }
        if (version <= UNVERSIONED) {
            return
        }
        flags = buffer.readUnsignedByte()
        ranges = List(buffer.readVarInt()) { readRanges(buffer) }
    }

    private fun readField(buffer: Reader): DbIndexField {
        val type = buffer.readUnsignedByte()
        return DbIndexField(type, List(buffer.readVarInt()) {
            val value = readValue(buffer, type)
            DbIndexValue(value.value, value.triple, IntArray(buffer.readVarInt()) { buffer.readVarInt() })
        })
    }

    private fun readRanges(buffer: Reader): DbIndexRanges {
        val type = buffer.readUnsignedByte()
        val valueCount = buffer.readVarInt()
        val rowIdCount = buffer.readVarInt()
        return DbIndexRanges(
            type,
            List(valueCount) { readValue(buffer, type) },
            IntArray(rowIdCount) { buffer.readVarInt() },
            IntArray(valueCount) { buffer.readVarInt() },
        )
    }

    private fun readValue(buffer: Reader, type: Int): DbIndexRangeValue = when (type) {
        INT -> DbIndexRangeValue(buffer.readInt())
        LONG -> DbIndexRangeValue(buffer.readLong())
        STRING -> DbIndexRangeValue(buffer.readString())
        TRIPLE -> DbIndexRangeValue(triple = intArrayOf(buffer.readUnsignedByte(), buffer.readInt(), buffer.readInt(), buffer.readInt()))
        else -> error("Dbtableindex value has unknown type $type at ${buffer.position()}")
    }

    companion object {
        const val COLUMN_BITS = 8
        const val COLUMN_MASK = 0xff
        const val VERSION_MARKER = 0xff

        /** The highest version with no ranges block, which is also what an unmarked file is. */
        const val UNVERSIONED = 1
        const val INT = 0
        const val LONG = 1
        const val STRING = 2
        const val TRIPLE = 3
    }
}

/** Every column index a table owns, gathered under the table's own id. */
class DbTableIndexDecoder : TypeDecoder<DbTableIndexType>(DBTABLEINDEX) {

    private val columns = DbColumnIndexDecoder()

    override fun create(size: Int) = Array(size) { DbTableIndexType(it) }

    override fun getArchive(id: Int) = id

    override fun size(cache: Cache) = cache.lastArchiveId(index)

    override fun load(definitions: Array<DbTableIndexType>, cache: Cache, id: Int) {
        val files = cache.files(index, id)
        if (files.isEmpty()) {
            return
        }
        val definition = definitions[id]
        recordDecode(id) {
            var trailing = 0
            val decoded = LinkedHashMap<Int, DbColumnIndexType>(files.size)
            for (column in files) {
                val data = cache.data(index, id, column) ?: continue
                val buffer = BufferReader(data)
                val entry = DbColumnIndexType((id shl DbColumnIndexDecoder.COLUMN_BITS) or column)
                columns.readLoop(entry, buffer)
                decoded[column] = entry
                trailing += buffer.remaining
            }
            definition.columns = decoded
            definition.rowIds = decoded.values.asSequence().flatMap { it.rowIds }.distinct().toList().toIntArray()
            trailing
        }
    }

    override fun DbTableIndexType.read(opcode: Int, buffer: Reader) = Unit
}
