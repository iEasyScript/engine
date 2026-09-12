package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class UnderlayType(
    override var id: Int = -1,
    var colour: Int = 0,
    var texture: Int = -1,
    var scale: Int = 512,
    var blockShadow: Boolean = true,
    var hue: Int = 0,
    var saturation: Int = 0,
    var lightness: Int = 0,
    var chroma: Int = 0
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}