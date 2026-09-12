package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.type.data.VarDomainType.Companion.TEMPORARY

data class VarClanSettingType(
    override var id: Int = -1,
    override var type: Int = -1,
    override var lifetime: Int = TEMPORARY,
    override var transmit: Int = 0,
) : VarDomainType {
    override var unusedRecords: Map<Int, List<ByteArray>>? = null
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}
