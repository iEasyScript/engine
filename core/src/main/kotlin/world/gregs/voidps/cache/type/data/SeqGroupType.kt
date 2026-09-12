package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class SeqGroupType(
    override var id: Int = -1,
    var field1: Int = 0,
    var transitions: IntArray? = null,
    var field3: Int = 0,
    var field4: Int = 0,
    var field5: Int = -1,
    var field6: Int = -1,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeqGroupType) return false
        if (id != other.id) return false
        if (field1 != other.field1) return false
        if (transitions != null) {
            if (other.transitions == null) return false
            if (!transitions.contentEquals(other.transitions)) return false
        } else if (other.transitions != null) return false
        if (field3 != other.field3) return false
        if (field4 != other.field4) return false
        if (field5 != other.field5) return false
        if (field6 != other.field6) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + field1
        result = 31 * result + (transitions?.contentHashCode() ?: 0)
        result = 31 * result + field3
        result = 31 * result + field4
        result = 31 * result + field5
        result = 31 * result + field6
        return result
    }
}
