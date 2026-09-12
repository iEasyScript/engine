package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class MapSceneType(
    override var id: Int = -1,
    var graphic: Int = 0,
    var colour: Int = 0,
    var unknown3: Boolean = false,
    var unknown5: Boolean = false
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}