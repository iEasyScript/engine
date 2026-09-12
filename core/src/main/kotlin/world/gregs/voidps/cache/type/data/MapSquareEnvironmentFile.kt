package world.gregs.voidps.cache.type.data

/**
 * The environment file: one fixed record of [SIZE] bytes, no count and no opcodes.
 *
 * The leading fields are the same quantities the legacy terrain file's environment tail carries in
 * a different order, which is where their names come from; everything the client does not name is
 * named by its position in the record. Widths and order are settled, meaning is not.
 */
class MapSquareEnvironmentFile(
    var sunColour: Int = 0,
    /** Three signed shorts. */
    var sunPosition: IntArray = IntArray(VECTOR),
    var sunAmbient: Int = 0,
    var sunLight: Int = 0,
    var sunBacklight: Int = 0,
    var unknown5: FloatArray = FloatArray(3),
    var fogColour: Int = 0,
    var fogDepth: Int = 0,
    /** Clear replaces [fogDepth] with the client's own sentinel. */
    var fogEnabled: Int = 0,
    var unknown9: FloatArray = FloatArray(4),
    /** Clear zeroes [unknown11] and [unknown12]. */
    var unknown10: Int = 0,
    var unknown11: FloatArray = FloatArray(4),
    /** Three vectors of three. */
    var unknown12: FloatArray = FloatArray(3 * VECTOR),
    var unknown13: FloatArray = FloatArray(8),
    var unknown14: Int = 0,
    var unknown15: Int = 0,
    var unknown16: Float = 0f,
    var unknown17: Int = 0,
    var unknown18: Int = 0,
    var unknown19: FloatArray = FloatArray(5),
    /** Three defaults and then three overrides, a non-zero override replacing its default. */
    var unknown20: FloatArray = FloatArray(6),
    var unknown21: Int = 0,
    var unknown22: Int = 0,
    var unknown23: Int = 0,
    var unknown24: Int = 0,
    var unknown25: Float = 0f,
    var unknown26: Int = 0,
    var unknown27: Float = 0f,
    var unknown28: Float = 0f,
    var unknown29: FloatArray = FloatArray(VECTOR)
) {

    companion object {
        /** Every environment file in the cache is exactly this long. */
        const val SIZE = 227

        const val VECTOR = 3
    }
}
