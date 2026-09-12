package world.gregs.voidps.cache.cs2

/**
 * Packs and unpacks the composite integers CS2 passes around.
 *
 * These are the values that otherwise read as magic numbers: an interface
 * component arrives as one int holding both the interface and the component
 * index, and a world position as one int holding plane, x and y.
 */
object Cs2Packing {

    fun interfaceOf(hash: Int): Int = hash ushr 16

    fun componentOf(hash: Int): Int = hash and 0xFFFF

    fun packComponent(interfaceId: Int, component: Int): Int =
        (interfaceId shl 16) or (component and 0xFFFF)

    /** True when the value carries an interface part worth spelling out. */
    fun isPackedComponent(hash: Int): Boolean = hash > 0xFFFF

    fun planeOf(coord: Int): Int = (coord ushr 28) and 0x3

    fun xOf(coord: Int): Int = (coord ushr 14) and 0x3FFF

    fun yOf(coord: Int): Int = coord and 0x3FFF

    fun packCoord(x: Int, y: Int, plane: Int): Int =
        ((plane and 0x3) shl 28) or ((x and 0x3FFF) shl 14) or (y and 0x3FFF)

    /** True when the value looks like a real position rather than a sentinel. */
    fun isPackedCoord(coord: Int): Boolean = coord > 0

    /** Structurally possible as a packed position: a level in range and both axes set. */
    fun looksLikeCoord(value: Int): Boolean =
        value > 0 && (value ushr 30) == 0 && xOf(value) > 0 && yOf(value) > 0

    /** The mapsquare the position a value decodes to would fall in. */
    fun mapSquareOf(value: Int): Int = (xOf(value) shr 6) or ((yOf(value) shr 6) shl 7)

    fun varDomainOf(operand: Int): Int = operand ushr 24

    fun varIdOf(operand: Int): Int = (operand ushr 8) and 0xFFFF

    fun varbitIdOf(operand: Int): Int = operand ushr 8

    fun packVar(domain: Int, id: Int, padding: Int): Int =
        (domain shl 24) or ((id and 0xFFFF) shl 8) or (padding and 0xFF)

    fun packVarbit(id: Int, padding: Int): Int = ((id and 0xFFFFFF) shl 8) or (padding and 0xFF)

    /**
     * The byte a var or varbit operand ends on. The dispatcher reads it and does
     * nothing with it, but it still has to come back out unchanged.
     */
    fun operandPaddingOf(operand: Int): Int = operand and 0xFF
}
