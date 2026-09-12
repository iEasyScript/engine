package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

/**
 * A config type the client reads out of the config index's effect animation group.
 *
 * `EffectAnimType` is a reverse engineered name, not an attested Jagex one, and the served records
 * fall into two disjoint shapes - one carrying [mode] through [signedMagnitude] and one carrying
 * [floatD] through [floatF] - so every field is nullable and a record says what it holds by which
 * fields it has. Fields whose meaning is not established are named for their position and payload.
 */
data class EffectAnimType(
    override var id: Int = -1,
    var mode: Int? = null,
    /** An angle in 1/4096 turn units. */
    var angle: Int? = null,
    var magnitude: Int? = null,
    var signedMagnitude: Int? = null,
    var floatA: Float? = null,
    var floatB: Float? = null,
    var floatC: Float? = null,
    var floatD: Float? = null,
    /** A record whose key is its whole value; the client stores a flag and reads no payload. */
    var flag: Boolean? = null,
    var valueA: Int? = null,
    var valueB: Int? = null,
    var floatE: Float? = null,
    var floatF: Float? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}
