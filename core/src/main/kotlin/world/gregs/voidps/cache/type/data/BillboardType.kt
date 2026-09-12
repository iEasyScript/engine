package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class BillboardType(
    override var id: Int = -1,
    var size2d: Int? = null,
    var size3d: Int? = null,
    var unknown3: Int? = null,
    var unknown4: Int? = null,
    var unknown5: Int? = null,
    var unknown7: Boolean = false,
    var material: Int? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    companion object {
        val EMPTY = BillboardType()
    }
}
