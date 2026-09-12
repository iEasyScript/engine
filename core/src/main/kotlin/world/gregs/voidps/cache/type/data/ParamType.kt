package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class ParamType(
    override var id: Int = -1,
    var type: Char = 0.toChar(),
    var typeId: Int = 0,
    var defaultInt: Int = 0,
    var defaultString: String? = null,
    var autoDisable: Boolean = true,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    companion object {
        val EMPTY = ParamType()
    }
}
