package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.DbColumnIndexType
import world.gregs.voidps.cache.type.data.DbColumnIndexType.Companion.UNMARKED
import world.gregs.voidps.cache.type.data.DbIndexField
import world.gregs.voidps.cache.type.data.DbIndexRanges
import world.gregs.voidps.cache.type.decoder.DbColumnIndexDecoder.Companion.INT
import world.gregs.voidps.cache.type.decoder.DbColumnIndexDecoder.Companion.LONG
import world.gregs.voidps.cache.type.decoder.DbColumnIndexDecoder.Companion.STRING
import world.gregs.voidps.cache.type.decoder.DbColumnIndexDecoder.Companion.TRIPLE
import world.gregs.voidps.cache.type.decoder.DbColumnIndexDecoder.Companion.UNVERSIONED
import world.gregs.voidps.cache.type.decoder.DbColumnIndexDecoder.Companion.VERSION_MARKER

class DbTableIndexEncoder : TypeEncoder<DbColumnIndexType> {

    override fun Writer.encode(definition: DbColumnIndexType) {
        if (definition.version != UNMARKED) {
            writeByte(VERSION_MARKER)
            writeByte(definition.version)
        }
        writeVarInt(definition.fields.size)
        for (field in definition.fields) {
            writeField(field)
        }
        if (definition.version <= UNVERSIONED) {
            return
        }
        writeByte(definition.flags)
        writeVarInt(definition.ranges.size)
        for (range in definition.ranges) {
            writeRanges(range)
        }
    }

    private fun Writer.writeField(field: DbIndexField) {
        writeByte(field.type)
        writeVarInt(field.values.size)
        for (value in field.values) {
            writeValue(field.type, value.value, value.triple)
            writeVarInt(value.rowIds.size)
            for (rowId in value.rowIds) {
                writeVarInt(rowId)
            }
        }
    }

    private fun Writer.writeRanges(ranges: DbIndexRanges) {
        writeByte(ranges.type)
        writeVarInt(ranges.values.size)
        writeVarInt(ranges.rowIds.size)
        for (value in ranges.values) {
            writeValue(ranges.type, value.value, value.triple)
        }
        for (rowId in ranges.rowIds) {
            writeVarInt(rowId)
        }
        for (length in ranges.lengths) {
            writeVarInt(length)
        }
    }

    private fun Writer.writeValue(type: Int, value: Any, triple: IntArray?) {
        when (type) {
            INT -> writeInt((value as Number).toInt())
            LONG -> writeLong((value as Number).toLong())
            STRING -> writeString(value as String)
            TRIPLE -> {
                val numbers = triple ?: error("Dbtableindex triple value has no numbers.")
                writeByte(numbers[0])
                writeInt(numbers[1])
                writeInt(numbers[2])
                writeInt(numbers[3])
            }
            else -> error("Dbtableindex value has unknown type $type.")
        }
    }
}
