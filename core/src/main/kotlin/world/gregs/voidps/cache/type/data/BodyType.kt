package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

data class BodyType(
    override var id: Int = -1,
    var disabledSlots: IntArray = IntArray(0),
    var unknown3: Int = -1,
    var unknown4: Int = -1,
    var unknown5: IntArray? = null,
    var unknown6: IntArray? = null
) : CacheType {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BodyType

        if (id != other.id) return false
        if (!disabledSlots.contentEquals(other.disabledSlots)) return false
        if (unknown3 != other.unknown3) return false
        if (unknown4 != other.unknown4) return false
        if (unknown5 != null) {
            if (other.unknown5 == null) return false
            if (!unknown5.contentEquals(other.unknown5)) return false
        } else if (other.unknown5 != null) return false
        if (unknown6 != null) {
            if (other.unknown6 == null) return false
            if (!unknown6.contentEquals(other.unknown6)) return false
        } else if (other.unknown6 != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + disabledSlots.contentHashCode()
        result = 31 * result + unknown3
        result = 31 * result + unknown4
        result = 31 * result + (unknown5?.contentHashCode() ?: 0)
        result = 31 * result + (unknown6?.contentHashCode() ?: 0)
        return result
    }
}