package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

data class ParticleProducerType(
    override var id: Int = -1,
    var unknown1a: Int? = null,
    var unknown1b: Int? = null,
    var unknown1c: Int? = null,
    var unknown1d: Int? = null,
    var unknown3a: Int? = null,
    var unknown3b: Int? = null,
    var unknown4a: Int? = null,
    var unknown4b: Int? = null,
    var unknown5: Int? = null,
    var unknown31a: Int? = null,
    var unknown31b: Int? = null,
    var unknown27: Int? = null,
    var unknown28: Int? = null,
    var unknown7a: Int? = null,
    var unknown7b: Int? = null,
    var unknown8a: Int? = null,
    var unknown8b: Int? = null,
    var unknown9: IntArray? = null,
    var unknown10: IntArray? = null,
    var unknown25: IntArray? = null,
    var unknown12: Int? = null,
    var unknown13: Int? = null,
    var unknown14: Int? = null,
    var unknown15: Int? = null,
    var unknown16a: Int? = null,
    var unknown16b: Int? = null,
    var unknown16c: Int? = null,
    var unknown16d: Int? = null,
    var unknown19: Int? = null,
    var unknown20: Int? = null,
    var unknown24: Int? = null,
    var unknown30: Boolean = false,
    var unknown32: Boolean = false,
    var unknown33: Boolean = false,
    var unknown34: Boolean = false,
    var unknown36: Boolean = false,
    var unknown26: Boolean = false,
    var unknown21: Int? = null,
    var unknown22: Int? = null,
    var unknown23: Int? = null,
    var unknown29: ByteArray? = null,
    var unknown35: ByteArray? = null,
    var unknown6a: Int? = null,
    var unknown6b: Int? = null,
    var unknown18: Int? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null

    companion object {
        val EMPTY = ParticleProducerType()
    }
}
