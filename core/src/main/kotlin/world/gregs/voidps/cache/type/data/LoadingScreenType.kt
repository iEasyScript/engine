package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * One thing a loading screen draws. [type] decides which of the remaining fields the file carries,
 * so an absent field is left null rather than given a value the file never held.
 */
data class LoadingScreenElement(
    val type: Int,
    val graphic: Int? = null,
    val unknownA: Int? = null,
    val unknownB: Int? = null,
    val stage: Int? = null,
    val unknownC: Int? = null,
    val alpha: Int? = null,
    val text: String? = null,
    val bytes: IntArray? = null,
    val ints: IntArray? = null
)

/**
 * A loading screen. Group 0 is the master table - a format byte, one byte per render type, and a
 * block of settings - and every other group is a list of screen elements, so which of the two
 * shapes a file has is decided by its group id rather than by anything in the bytes.
 */
data class LoadingScreenType(
    override var id: Int = -1,
    var format: Int = -1,
    var renderTypes: IntArray? = null,
    var unknown1: Int = -1,
    var unknown2: Int = -1,
    var unknown3: Int = -1,
    var unknown4: Int = -1,
    var unknown5: Int = -1,
    var unknown6: Int = -1,
    var unknown7: Int = -1,
    var unknown8: Int = -1,
    var unknown9: Int = -1,
    var unknown10: Int = -1,
    var unknown11: Int = -1,
    var unknown12: Int = -1,
    var unknown13: Int = -1,
    var unknown14: Int = -1,
    var unknown15: Int = -1,
    var elements: Array<LoadingScreenElement>? = null,
) : CacheType {
    companion object {
        const val MASTER = 0
        val EMPTY = LoadingScreenType()
    }
}
