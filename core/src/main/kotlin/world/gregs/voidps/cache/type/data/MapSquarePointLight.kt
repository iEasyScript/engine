package world.gregs.voidps.cache.type.data

/**
 * A point light of the light file - the modern form of the legacy terrain tail's light record, with
 * the same head and a run of floats behind it.
 *
 * [ranges] holds `radius * 2 + 1` rows of the light's square footprint, the high byte of each the
 * row's first covered column and the low byte its run length; a set of rows that covers every column
 * is an identity cover the client discards. [lightType] is on the wire only when the low five bits
 * of [packedType] are all set, and is [NO_LIGHT_TYPE] otherwise.
 */
class MapSquarePointLight(
    val flags: Int,
    val x: Int,
    val z: Int,
    val height: Int,
    val radius: Int,
    val ranges: IntArray,
    val colour: Int,
    val packedType: Int,
    val unknown7: Int,
    val lightType: Int,
    val unknown9: FloatArray,
    val unknown10: Int,
    val unknown11: Float,
    val unknown12: Int,
    val unknown13: FloatArray,
    /** Three signed shorts. */
    val unknown14: IntArray,
    val unknown15: Int
) {

    companion object {
        /** The type that means the light's parameters come from the light config list. */
        const val CONFIG_TYPE = 0x1f

        const val NO_LIGHT_TYPE = -1
    }
}

/**
 * One record of the patch file: a placement, an axis and an angle the client turns into a rotation,
 * and a type id.
 *
 * The four floats are an **axis and an angle**, not a stored quaternion - the client builds the
 * quaternion at load time from the half angle's sine and cosine.
 */
class MapSquarePatch(
    val positionX: Int,
    val positionY: Int,
    val extentX: Int,
    val extentY: Int,
    val unknown4: Int,
    /** Three floats. */
    val axis: FloatArray,
    val angle: Float,
    val unknown6: Int,
    val directionX: Int,
    val directionY: Int,
    val typeId: Int
) {

    companion object {
        /** Every patch record in the cache is exactly this long. */
        const val SIZE = 28
    }
}
