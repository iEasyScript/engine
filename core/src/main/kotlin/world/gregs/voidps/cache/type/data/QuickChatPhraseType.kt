package world.gregs.voidps.cache.type.data

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Extra
import world.gregs.voidps.cache.type.OpcodeOrdered

data class QuickChatPhraseType(
    override var id: Int = -1,
    var stringParts: Array<String>? = null,
    var responses: IntArray? = null,
    var ids: Array<IntArray>? = null,
    var types: IntArray? = null,
    var flag: Boolean = true,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null
) : CacheType, Extra, OpcodeOrdered {

    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    fun buildString(enums: Array<EnumType>, items: Array<ObjType>, data: ByteArray) = buildString(80) {
        val parts = stringParts
        if (parts != null) {
            val types = types
            val ids = ids
            if (types != null && ids != null) {
                val reader = BufferReader(data)
                for (index in types.indices) {
                    append(parts[index])
                    val type = QuickChatParamType.getType(types[index]) ?: continue
                    val value = reader.readValue(type.valueBytes)
                    append(
                        when (type.value) {
                            QuickChatValue.ENUM_LOOKUP -> enums[ids[index].first()].getString(value)
                            QuickChatValue.OBJ_NAME -> items[value].name
                            QuickChatValue.DECIMAL -> value.toString()
                            QuickChatValue.UNUSED -> ""
                        }
                    )
                }
            }
            append(parts.last())
        }
    }

    fun getType(index: Int): QuickChatParamType? {
        return QuickChatParamType.getType(types?.getOrNull(index) ?: return null)
    }

    override fun toString(): String {
        return "QuickChatPhraseType(id=$id, stringParts=${stringParts?.contentToString()}, responses=${responses?.contentToString()}, ids=${ids?.contentDeepToString()}, types=${types?.contentToString()}, flag=$flag)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as QuickChatPhraseType

        if (id != other.id) return false
        if (flag != other.flag) return false
        if (!stringParts.contentEquals(other.stringParts)) return false
        if (!responses.contentEquals(other.responses)) return false
        if (!types.contentEquals(other.types)) return false
        if (ids != null) {
            if (other.ids == null) return false
            if (!ids.contentDeepEquals(other.ids)) return false
        } else if (other.ids != null) return false
        if (stringId != other.stringId) return false
        if (extras != other.extras) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + (stringParts?.contentHashCode() ?: 0)
        result = 31 * result + (responses?.contentHashCode() ?: 0)
        result = 31 * result + (ids?.contentDeepHashCode() ?: 0)
        result = 31 * result + (types?.contentHashCode() ?: 0)
        result = 31 * result + flag.hashCode()
        result = 31 * result + stringId.hashCode()
        result = 31 * result + (extras?.hashCode() ?: 0)
        return result
    }

    companion object {
        val EMPTY = QuickChatPhraseType()
    }
}

private fun Reader.readValue(bytes: Int): Int {
    var value = 0
    repeat(bytes) { value = (value shl 8) or readUnsignedByte() }
    return value
}
