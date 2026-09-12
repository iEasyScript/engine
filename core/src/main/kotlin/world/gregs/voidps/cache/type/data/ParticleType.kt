package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class ParticleType(
    override var id: Int = -1,
    var unknown1: Int? = null,
    var unknown4Type: Int? = null,
    var unknown4Value: Int? = null,
    var unknown6: Boolean = false,
    var unknown2: Boolean = false,
    var unknown8: Boolean = false,
    var unknown9: Boolean = false,
    var unknown10: Boolean = false,
    var vector: ParticleVector? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    companion object {
        val EMPTY = ParticleType()
    }
}
