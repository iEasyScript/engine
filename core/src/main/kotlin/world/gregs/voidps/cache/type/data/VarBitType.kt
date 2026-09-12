package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class VarBitType(
    override var id: Int = -1,
    var index: Int = 0,
    var startBit: Int = 0,
    var endBit: Int = 0,
    var flags: Int = 0,
    var domainId: Byte = 0,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    val domain: VarDomain? get() = VarDomain.forId(domainId.toInt())

    var baseVar: Int
        get() = index
        set(value) {
            index = value
        }

    val maxValue: Int get() = BIT_MASKS[endBit - startBit]

    fun getValue(varValue: Int): Int = (varValue shr startBit) and BIT_MASKS[endBit - startBit]

    companion object {
        val EMPTY = VarBitType()

        val BIT_MASKS = IntArray(32).apply {
            var current = 2
            for (i in 0..31) {
                this[i] = current - 1
                current += current
            }
        }

        val baseVarMap = mutableMapOf<VarDomain, MutableMap<Int, MutableSet<VarBitType>>>()

        fun loadBaseVarMap() {
            baseVarMap.clear()
            for (def in Cache.varbits) {
                val domain = def.domain ?: continue
                baseVarMap.getOrPut(domain) { mutableMapOf() }
                    .getOrPut(def.baseVar) { mutableSetOf() }
                    .add(def)
            }
        }
    }
}
