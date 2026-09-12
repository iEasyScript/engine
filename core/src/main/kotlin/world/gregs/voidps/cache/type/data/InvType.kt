package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Extra
import world.gregs.voidps.cache.type.OpcodeOrdered

data class InvType(
    override var id: Int = -1,
    var length: Int = 0,
    var ids: IntArray? = null,
    var amounts: IntArray? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null
) : CacheType, Extra, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InvType

        if (id != other.id) return false
        if (length != other.length) return false
        if (ids != null) {
            if (other.ids == null) return false
            if (!ids.contentEquals(other.ids)) return false
        } else if (other.ids != null) return false
        if (amounts != null) {
            if (other.amounts == null) return false
            if (!amounts.contentEquals(other.amounts)) return false
        } else if (other.amounts != null) return false
        if (stringId != other.stringId) return false
        if (extras != other.extras) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + length
        result = 31 * result + (ids?.contentHashCode() ?: 0)
        result = 31 * result + (amounts?.contentHashCode() ?: 0)
        result = 31 * result + stringId.hashCode()
        result = 31 * result + extras.hashCode()
        return result
    }

    val size: Int get() = length

    val items: Map<Int, Int>
        get() {
            val ids = ids ?: return emptyMap()
            val amounts = amounts ?: return emptyMap()
            val map = LinkedHashMap<Int, Int>(ids.size)
            for (i in ids.indices) {
                map[ids[i]] = amounts.getOrElse(i) { 0 }
            }
            return map
        }

    companion object {
        val EMPTY = InvType()
    }
}