package world.gregs.voidps.cache.type.data

data class MapSquareObject(
    val id: Int,
    val localX: Int,
    val localY: Int,
    val plane: Int,
    val shape: Int,
    val rotation: Int,
    /** The placement override the object's data byte announced, or null when it announced none. */
    val transform: MapSquareTransform? = null
)

/**
 * A placed object's optional override of the rotation, offset and scale its shape would give it.
 *
 * Every field is a null when its flag bit was clear, so an override that carries nothing at all -
 * which two of every five in the cache do - is an object with a non-null transform whose fields are
 * all null, and re-encoding it writes the empty flag byte the file held. [scale] and the three axis
 * scales are exclusive: the file either scales all three axes by one value or names them one by one.
 */
class MapSquareTransform(
    /** Four signed shorts, the client's `/ 32768` quaternion. */
    var rotation: IntArray? = null,
    var translateX: Int? = null,
    var translateY: Int? = null,
    var translateZ: Int? = null,
    var scale: Int? = null,
    var scaleX: Int? = null,
    var scaleY: Int? = null,
    var scaleZ: Int? = null
) {

    /** The flag byte, rebuilt from which fields are present; never stored. */
    fun flags(): Int {
        var flags = 0
        if (rotation != null) flags = flags or 0x1
        if (translateX != null) flags = flags or 0x2
        if (translateY != null) flags = flags or 0x4
        if (translateZ != null) flags = flags or 0x8
        if (scale != null) {
            return flags or 0x10
        }
        if (scaleX != null) flags = flags or 0x20
        if (scaleY != null) flags = flags or 0x40
        if (scaleZ != null) flags = flags or 0x80
        return flags
    }

    companion object {
        /** How many signed shorts the quaternion holds. */
        const val ROTATION_SIZE = 4
    }
}
