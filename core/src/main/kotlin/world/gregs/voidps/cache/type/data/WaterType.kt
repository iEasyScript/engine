package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.UnusedRecords

data class WaterType(
    override var id: Int = -1,
    var overlayFlags: Boolean = false,
    var underlayFlags: Boolean = false,
    var heightScale: Int = 0,
    var heightsFlag: Boolean = false,
) : CacheType, UnusedRecords {
    override var unusedRecords: Map<Int, List<ByteArray>>? = null
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}
