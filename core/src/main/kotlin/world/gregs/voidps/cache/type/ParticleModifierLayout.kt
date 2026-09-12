package world.gregs.voidps.cache.type

import world.gregs.voidps.cache.type.ParticleField.BYTE
import world.gregs.voidps.cache.type.ParticleField.FLOAT
import world.gregs.voidps.cache.type.ParticleField.INTEGER
import world.gregs.voidps.cache.type.ParticleField.SCALAR_CURVE
import world.gregs.voidps.cache.type.ParticleField.SHAPE
import world.gregs.voidps.cache.type.ParticleField.VECTOR2_CURVE
import world.gregs.voidps.cache.type.ParticleField.VECTOR3_CURVE
import world.gregs.voidps.cache.type.ParticleField.VECTOR4_CURVE

internal enum class ParticleField(val dimensions: Int) {
    BYTE(0),
    INTEGER(0),
    FLOAT(0),
    SHAPE(0),
    SCALAR_CURVE(1),
    VECTOR2_CURVE(2),
    VECTOR3_CURVE(3),
    VECTOR4_CURVE(4),
}

/** The payload each emitter modifier type carries, in the order the client reads it. */
internal object ParticleModifierLayout {

    private val byType: Map<Int, List<ParticleField>> = mapOf(
        0 to listOf(BYTE, SHAPE, SHAPE) + floats(9),
        1 to listOf(VECTOR3_CURVE, SCALAR_CURVE, VECTOR3_CURVE, SCALAR_CURVE),
        2 to listOf(SCALAR_CURVE, SCALAR_CURVE),
        3 to listOf(BYTE, SCALAR_CURVE, VECTOR3_CURVE, VECTOR3_CURVE),
        4 to listOf(VECTOR3_CURVE, SCALAR_CURVE),
        5 to listOf(FLOAT, FLOAT, SCALAR_CURVE, SCALAR_CURVE),
        6 to listOf(BYTE) + floats(4) + List(4) { SCALAR_CURVE },
        7 to listOf(SCALAR_CURVE, SCALAR_CURVE),
        8 to listOf(BYTE),
        9 to listOf(FLOAT, FLOAT, SCALAR_CURVE, SCALAR_CURVE),
        10 to List(3) { BYTE },
        11 to listOf(SHAPE, SHAPE) + floats(4) + BYTE,
        12 to listOf(BYTE, BYTE, FLOAT, FLOAT, VECTOR4_CURVE),
        13 to floats(4) + BYTE + VECTOR4_CURVE + SCALAR_CURVE,
        14 to listOf(BYTE, INTEGER, INTEGER),
        15 to listOf(BYTE, FLOAT, FLOAT, SCALAR_CURVE, SHAPE, FLOAT, FLOAT, BYTE),
        16 to listOf(BYTE, FLOAT, FLOAT, SCALAR_CURVE, BYTE, FLOAT, FLOAT, SCALAR_CURVE) + floats(3),
        17 to listOf(BYTE, BYTE, FLOAT, FLOAT, VECTOR2_CURVE),
    )

    fun of(type: Int): List<ParticleField> =
        byType[type] ?: throw IllegalArgumentException("Unknown particle modifier type $type.")

    private fun floats(count: Int) = List(count) { FLOAT }
}

/** How many components of a shape's extents its kind carries. */
internal fun particleShapeExtents(kind: Int): Int = when (kind) {
    1, 4, 6, 7 -> 1
    2 -> 2
    3, 5, 8 -> 3
    else -> 0
}
