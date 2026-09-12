package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class IDKType(
    override var id: Int = -1,
    var bodyPart: Int = -1,
    var modelIds: IntArray? = null,
    var nonSelectable: Boolean = false,
    var recolorSrc: IntArray? = null,
    var recolorDst: IntArray? = null,
    var retextureSrc: IntArray? = null,
    var retextureDst: IntArray? = null,
    var headModelIds: IntArray = IntArray(5) { -1 },
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IDKType
        return id == other.id
    }

    override fun hashCode(): Int = id

    companion object {
        val EMPTY = IDKType()
    }
}
