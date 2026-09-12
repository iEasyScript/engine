package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.OpcodeOrdered

/** A signed pair of the group 3 table whose length group 3's own count record fixes. */
data class DefaultsPair(val first: Int, val second: Int)

/** One cell of a group 3 ramp block: a value and the ids that follow it. */
data class DefaultsRamp(val value: Int, val ids: IntArray)

/**
 * A group 7 light source. [kind] selects which of the remaining fields the file carries: 0 has
 * [first], [second] and [ids]; 1 has [first] and [second]; 2 has [ids]; any other kind ends the
 * record after the kind byte itself.
 */
data class DefaultsLight(val kind: Int, val first: Int, val second: Int, val ids: IntArray?)

/** One group 9 experience curve: which slot it replaces and the experience at each level. */
data class DefaultsCurve(val index: Int, val experience: IntArray)

/**
 * One group 9 skill. [flags] decides which of [softCap], [curve] and [levelOffset] the file
 * carries, so it stays authoritative rather than being derived back from them.
 */
data class DefaultsSkill(
    val skill: Int,
    val levelCap: Int,
    val flags: Int,
    val softCap: Int,
    val curve: Int,
    val levelOffset: Int,
    val trailing: Int
)

data class DefaultsGroup1Type(
    override var id: Int = -1,
    var unknown1: Int = -1,
    var unknown10: Int = -1,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

data class DefaultsGroup2Type(
    override var id: Int = -1,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

data class DefaultsGroup3Type(
    override var id: Int = -1,
    var pairCount: Int = -1,
    var pairs: Array<DefaultsPair>? = null,
    var unknown2: Int? = null,
    var unknown4: Boolean = false,
    var unknown5: Int = -1,
    var unknown6: Int = -1,
    var ramps7: Array<DefaultsRamp>? = null,
    var unknown8: Boolean = false,
    var unknown9: Int = -1,
    var unknown10: Boolean = false,
    var unknown11: Int = -1,
    var unknown12First: Int = -1,
    var unknown12Second: Int = -1,
    var unknown13: Int = -1,
    var unknown14: Int = -1,
    var unknown15: Int = -1,
    var unknown16: Boolean = false,
    var unknown17: Int? = null,
    var unknown18: Int? = null,
    var unknown19: Int? = null,
    var unknown20First: Int = -1,
    var unknown20Second: Int = -1,
    var unknown21: Int = -1,
    var unknown22Head: IntArray? = null,
    var unknown22FirstOffset: Int = 0,
    var unknown22SecondOffset: Int = 0,
    var unknown22Tail: IntArray? = null,
    var ramps23: Array<DefaultsRamp>? = null,
    var unknown24: Int? = null,
    var unknown25: IntArray? = null,
    var unknown26: Int? = null,
    var unknown27: Int? = null,
    var unknown28: Int? = null,
    var unknown29First: Int? = null,
    var unknown29Second: Int? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

data class DefaultsGroup4Type(
    override var id: Int = -1,
    var unknown1: Int? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

data class DefaultsGroup5Type(
    override var id: Int = -1,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

/**
 * Group 6 - the equipment slot table `BodyType` also reads. Record 7 carries no count of its own
 * and is as long as [slots], so a file that lists it before record 1 leaves it empty.
 */
data class DefaultsGroup6Type(
    override var id: Int = -1,
    var slots: IntArray? = null,
    var unknown3: Int = -1,
    var unknown4: Int = -1,
    var unknown5: IntArray? = null,
    var unknown6: IntArray? = null,
    var unknown7: IntArray? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

/**
 * Group 7 - light source defaults. Records 8, 9 and 10 are read and thrown away by the client, so
 * they are kept whole here: without them the file cannot be rebuilt.
 */
data class DefaultsGroup7Type(
    override var id: Int = -1,
    var light1: DefaultsLight? = null,
    var light2: DefaultsLight? = null,
    var light3: DefaultsLight? = null,
    var light4: DefaultsLight? = null,
    var ids5: IntArray? = null,
    var ids6: IntArray? = null,
    var ids7: IntArray? = null,
    var discarded8: DefaultsLight? = null,
    var discarded9: DefaultsLight? = null,
    var discarded10: DefaultsLight? = null,
    var unknown11: Boolean = false,
    var unknown12: Int? = null,
    var unknown13: Int? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

/** Group 8 - served but never fetched, so the record split is this reading, not the client's. */
data class DefaultsGroup8Type(
    override var id: Int = -1,
    var unknown1: Int = -1,
    var unknown13: Int = -1,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

/** Group 9 - skills and their experience curves. Record 2 must precede record 1. */
data class DefaultsGroup9Type(
    override var id: Int = -1,
    var curveCount: Int = -1,
    var curves: Array<DefaultsCurve>? = null,
    var skills: Array<DefaultsSkill>? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

/**
 * Group 10 - seven scalars then a fifteen cell grid whose record number encodes the cell, so
 * [grid] is indexed by cell rather than by the order the file listed the cells in.
 */
data class DefaultsGroup10Type(
    override var id: Int = -1,
    var unknown1: Int? = null,
    var colour2: Int? = null,
    var colour3: Int? = null,
    var unknown4: Int = -1,
    var unknown5: Int = -1,
    var unknown6: Int? = null,
    var colour7: Int? = null,
    var grid: IntArray? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}

data class DefaultsGroup12Type(
    override var id: Int = -1,
    var unknown1First: Int? = null,
    var unknown1Second: Int? = null,
    var unknown2: IntArray? = null,
) : CacheType, OpcodeOrdered {
    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
}
