package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.Index.ENUMS
import world.gregs.voidps.cache.type.data.EnumEntry
import world.gregs.voidps.cache.type.data.EnumType
import world.gregs.voidps.cache.type.encoder.EnumEncoder

class EnumDecoder : TypeDecoder<EnumType>(ENUMS) {
    override fun create(size: Int) = Array(size) { EnumType(it) }

    private val encoder = EnumEncoder()

    override fun canonicalOpcodes(definition: EnumType): IntArray = encoder.opcodes(definition)

    override fun getFile(id: Int) = id and 0xff

    override fun getArchive(id: Int) = id ushr 8

    override fun EnumType.read(opcode: Int, buffer: Reader) {
        when (opcode) {
            1 -> keyType = byteToChar(buffer.readByte().toByte())
            2 -> valueType = byteToChar(buffer.readByte().toByte())
            3 -> defaultString = buffer.readString()
            4 -> defaultInt = buffer.readInt()
            5, 6 -> {
                length = buffer.readUnsignedShort()
                readEntries(buffer, opcode == 5) { buffer.readInt() }
            }
            7, 8 -> {
                arraySize = buffer.readUnsignedShort()
                length = buffer.readUnsignedShort()
                readEntries(buffer, opcode == 7) { buffer.readUnsignedShort() }
            }
            101 -> keyTypeId = buffer.readSmart()
            102 -> valueTypeId = buffer.readSmart()
            else -> unknown(opcode, buffer)
        }
    }

    /** Insertion ordered, and with the records kept whenever the file listed a key twice. */
    private fun EnumType.readEntries(buffer: Reader, strings: Boolean, key: () -> Int) {
        val values = LinkedHashMap<Int, Any>(length)
        val records = ArrayList<EnumEntry>(length)
        for (count in 0 until length) {
            val id = key()
            val value: Any = if (strings) buffer.readString() else buffer.readInt()
            values[id] = value
            records.add(EnumEntry(id, value))
        }
        map = values
        if (values.size != records.size) {
            entries = records
        }
    }
}