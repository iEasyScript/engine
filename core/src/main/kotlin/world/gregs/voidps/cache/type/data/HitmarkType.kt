package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.type.Transforms
import world.gregs.voidps.cache.type.OpcodeOrdered

enum class HitmarkReplacement {
    DROP_NEW,
    SOONEST_TO_EXPIRE,
    LOWEST_DAMAGE_IF_NEW_IS_HIGHER,
    FIRST_SLOT;

    companion object {
        fun of(value: Int) = when (value) {
            -1 -> DROP_NEW
            0 -> SOONEST_TO_EXPIRE
            1 -> LOWEST_DAMAGE_IF_NEW_IS_HIGHER
            else -> FIRST_SLOT
        }
    }
}

data class HitmarkType(
    override var id: Int = -1,
    var field1: Int = -1,
    var hasColour: Boolean = false,
    var colour: Int = 0xffffff,
    var field3: Int = -1,
    var field4: Int = -1,
    var field5: Int = -1,
    var field6: Int = -1,
    var field7: Int = 0,
    var text: String = "",
    var displayDuration: Int = 70,
    var field10: Int = 0,
    var replacementValue: Int = -1,
    var field13: Int = 0,
    var field14: Int = -1,
    var field16a: Int = 0,
    var field16b: Int = 0,
    override var varbit: Int = -1,
    override var varp: Int = -1,
    override var transforms: IntArray? = null,
    var field19: Int = 1,
    var field20: Int = 1,
) : CacheType, Transforms, OpcodeOrdered {
    val replacement: HitmarkReplacement get() = HitmarkReplacement.of(replacementValue)

    override var opcodeOrder: IntArray? = null
    override var shadowedPayloads: Array<ByteArray?>? = null
    companion object {
        val EMPTY = HitmarkType()
    }
}
