package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class CursorType(
    override var id: Int = -1,
    var graphicId: Int = -1,
    var hotspotX: Int = 0,
    var hotspotY: Int = 0,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    companion object {
        val EMPTY = CursorType()
    }
}
