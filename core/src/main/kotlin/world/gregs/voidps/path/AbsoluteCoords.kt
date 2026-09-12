package world.gregs.voidps.path

@Suppress("MemberVisibilityCanBePrivate")
@JvmInline
value class AbsoluteCoords(val packedCoord: Int) {
    constructor(
        x: Int,
        y: Int,
        z: Int,
    ) : this((y and 0x3FFF) or ((x and 0x3FFF) shl 14) or ((z and 0x3) shl 28))

    val x: Int get() = (packedCoord shr 14) and 0x3FFF
    val y: Int get() = packedCoord and 0x3FFF
    val z: Int get() = (packedCoord shr 28) and 0x3

    fun toZoneCoords(): ZoneCoords = ZoneCoords(x shr 3, y shr 3, z)
    override fun toString(): String = "AbsoluteCoords($x, $y, $z)"
}
