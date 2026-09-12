package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class HeadbarType(
    override var id: Int = -1,
    var sortKey: Int = 255,
    var field3: Int = 255,
    var fadeStart: Int = -1,
    var fadeEnd: Int = 70,
    var fadeStartDelay: Int = -1,
    var sizeClass0Fill: Int = -1,
    var sizeClass0Frame: Int = -1,
    var sizeClass1Fill: Int = -1,
    var sizeClass1Frame: Int = -1,
    var sizeClass2Fill: Int = -1,
    var sizeClass2Frame: Int = -1,
    var centredOverlay: Int = -1,
    var fillEdgeCap: Int = -1,
    var useAlternateBlitter: Boolean = false,
    var barHeightPx: Int = 2,
    var tweenQuantisation: Int = 1,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    companion object {
        val EMPTY = HeadbarType()
    }
}
