package world.gregs.voidps.cache.type

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.buffer.write.Writer

interface Parameterized {
    var params: Map<Int, Any>?

    /** The records as the file listed them, kept only when it listed one param id more than once. */
    var paramRecords: List<ParamRecord>?

    fun getParamInt(id: Int, default: Int = 0): Int = params?.get(id) as? Int ?: default

    fun getParamString(id: Int, default: String? = null): String? = params?.get(id) as? String ?: default

    fun readParameters(buffer: Reader) {
        val length = buffer.readUnsignedByte()
        if (length == 0) {
            return
        }
        val params = Int2ObjectArrayMap<Any>()
        val records = ArrayList<ParamRecord>(length)
        for (i in 0 until length) {
            val string = buffer.readUnsignedBoolean()
            val id = buffer.readUnsignedMedium()
            val value: Any = if (string) buffer.readString() else buffer.readInt()
            params[id] = value
            records.add(ParamRecord(id, value))
        }
        this.params = params
        if (params.size != records.size) {
            paramRecords = records
        }
    }

    fun writeParameters(writer: Writer) {
        params?.let { params ->
            writer.writeByte(249)
            writer.writeByte(params.size)
            params.forEach { (id, value) ->
                writer.writeByte(value is String)
                writer.writeMedium(id)
                if (value is String) {
                    writer.writeString(value)
                } else if (value is Int) {
                    writer.writeInt(value)
                }
            }
        }
    }
}
