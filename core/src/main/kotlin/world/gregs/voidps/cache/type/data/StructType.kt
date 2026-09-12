package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Parameterized
import world.gregs.voidps.cache.type.OpcodeOrdered
import world.gregs.voidps.cache.type.ParamRecord

data class StructType(
    override var id: Int = -1,
    override var params: Map<Int, Any>? = null,
) : CacheType, Parameterized, OpcodeOrdered {
    override var paramRecords: List<ParamRecord>? = null
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    @JvmOverloads
    fun getIntValue(key: Int, default: Int = 0): Int = params?.get(key) as? Int ?: default

    fun getStringValue(key: Int): String? = params?.get(key) as? String

    fun getValues(): Map<Int, Any> = params ?: emptyMap()

    companion object {
        val EMPTY = StructType()
    }
}
