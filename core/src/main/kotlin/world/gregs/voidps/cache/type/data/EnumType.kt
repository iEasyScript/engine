package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Extra
import world.gregs.voidps.cache.type.OpcodeOrdered

data class EnumType(
    override var id: Int = -1,
    var keyType: Char = 0.toChar(),
    var valueType: Char = 0.toChar(),
    var keyTypeId: Int = -1,
    var valueTypeId: Int = -1,
    var defaultString: String = "null",
    var defaultInt: Int = 0,
    var length: Int = 0,
    var map: Map<Int, Any>? = null,
    override var stringId: String = "",
    override var extras: Map<String, Any>? = null,
    var arraySize: Int = 0,
    var entries: List<EnumEntry>? = null,
) : CacheType, Extra, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    @Suppress("UNCHECKED_CAST")
    val values: MutableMap<Int, Any>
        get() = (map as? MutableMap<Int, Any>) ?: mutableMapOf()

    fun getKey(value: Any) = map?.filterValues { it == value }?.keys?.lastOrNull() ?: -1

    fun getInt(id: Int) = map?.get(id) as? Int ?: defaultInt

    fun randomInt() = map?.values?.random() as? Int ?: defaultInt

    fun getString(id: Int) = map?.get(id) as? String ?: defaultString

    fun getStringValue(id: Int) = getString(id)

    fun getIntValue(id: Int) = getInt(id)

    fun getIntValueAtIndex(id: Int) = getInt(id)

    fun getValue(id: Any): Any? = map?.get(id as? Int ?: return null)

    fun getDefaultIntValue(): Int = defaultInt

    fun getSize(): Int = map?.size ?: 0

    fun getKeyForValue(value: Any): Int {
        val m = map ?: return -1
        for ((k, v) in m) {
            if (v == value) return k
        }
        return -1
    }

    companion object {
        val EMPTY = EnumType()
    }
}