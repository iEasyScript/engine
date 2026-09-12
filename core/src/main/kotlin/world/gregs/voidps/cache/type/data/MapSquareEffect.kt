package world.gregs.voidps.cache.type.data

/**
 * One record of the environment block that follows the tile records of a surface terrain file.
 *
 * The block is an eight byte head and then a bare list of records - an opcode byte and its payload,
 * running to the end of the file with no count and no terminator. Everything is the value **as
 * stored**: colours are still packed, positions are still in 1/128 of a tile, and an absent field is
 * null rather than the default the client substitutes, because only presence tells a file whose sun
 * colour happens to equal the default from one whose flag bit was clear.
 */
sealed interface MapSquareEffect {

    /** The opcode byte the record is introduced by. */
    val opcode: Int
}

/**
 * A null field is one whose flag bit was clear, so the file did not carry it and re-encoding must
 * not add it - see [flags], which is derived from presence and never stored.
 */
class MapSquareEnvironment(
    var sunColour: Int? = null,
    var sunAmbient: Int? = null,
    var sunLight: Int? = null,
    var sunBacklight: Int? = null,
    /** Three signed shorts. */
    var sunPosition: IntArray? = null,
    var fogColour: Int? = null,
    var fogDepth: Int? = null,
    var unknown7: Int? = null
) : MapSquareEffect {

    override val opcode: Int
        get() = OPCODE

    /**
     * The flag byte, rebuilt from which fields are present. All eight bits are accounted for, so
     * the byte carries exactly the information the eight fields' presence does and is never stored.
     */
    fun flags(): Int {
        var flags = 0
        if (sunColour != null) flags = flags or 0x1
        if (sunAmbient != null) flags = flags or 0x2
        if (sunLight != null) flags = flags or 0x4
        if (sunBacklight != null) flags = flags or 0x8
        if (sunPosition != null) flags = flags or 0x10
        if (fogColour != null) flags = flags or 0x20
        if (fogDepth != null) flags = flags or 0x40
        if (unknown7 != null) flags = flags or 0x80
        return flags
    }

    companion object {
        const val OPCODE = 0

        /** How many signed shorts the sun position holds. */
        const val SUN_POSITION_SIZE = 3
    }
}

/** The count is an unsigned byte, so one record holds at most 255 lights. */
class MapSquareLights(val lights: MutableList<MapSquareLight> = mutableListOf()) : MapSquareEffect {

    override val opcode: Int
        get() = OPCODE

    companion object {
        const val OPCODE = 1
    }
}

/** A point light exactly as stored. */
class MapSquareLight(
    /** `and 0x7` the level, and two bits saying which way the light grows. */
    val packedLevel: Int,
    val x: Int,
    val z: Int,
    val heightOffset: Int,
    /** The light has `packedRadius * 2 + 1` range slices. */
    val packedRadius: Int,
    /** `packedRadius * 2 + 1` unsigned shorts: the high byte the upper bound, the low the lower. */
    val ranges: IntArray,
    val colour: Int,
    /** `and 0x1f` the type, the high bits a rotation offset. */
    val packedType: Int,
    val unknown8: Int,
    /** On the wire only when the type is [CONFIG_TYPE]; -1 otherwise. */
    val lightType: Int
) {

    companion object {
        /** The type that means "the flicker parameters come from the light config list". */
        const val CONFIG_TYPE = 0x1f
    }
}

/** Three floats the client scales the tone mapping by. */
class MapSquareHdr(val bloom: Float, val brightpass: Float, val whitePoint: Float) : MapSquareEffect {

    override val opcode: Int
        get() = OPCODE

    companion object {
        const val OPCODE = 2
    }
}

class MapSquareUnknown3(val unknown0: Int, val unknown1: Float) : MapSquareEffect {

    override val opcode: Int
        get() = OPCODE

    companion object {
        const val OPCODE = 3
    }
}

/** [id] is unsigned, the three coordinates and the rotation signed shorts. */
class MapSquareSkybox(
    val id: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val rotation: Int
) : MapSquareEffect {

    override val opcode: Int
        get() = OPCODE

    companion object {
        const val OPCODE = 0x80
    }
}

/** Always [MapSquareType.LEVELS] levels, each with its own mode byte. */
class MapSquareLightGrid(val levels: MutableList<MapSquareLightGridLevel> = mutableListOf()) : MapSquareEffect {

    override val opcode: Int
        get() = OPCODE

    companion object {
        const val OPCODE = 0x81
    }
}

/**
 * One level of a light grid record: mode 0 is unlit and carries nothing, every other mode carries
 * [SAMPLE_COUNT] signed intensities, each covering a [BLOCK] by [BLOCK] square of tiles.
 *
 * The mode stays the byte the file held rather than an enum: a value nobody has seen still has to
 * survive the round trip.
 */
class MapSquareLightGridLevel(val mode: Int, val samples: ByteArray?) {

    companion object {
        const val MODE_UNLIT = 0

        /** Each sample covers a 4 by 4 square of tiles. */
        const val BLOCK = 4

        const val SAMPLE_COUNT = (MapSquareTiles.SIZE / BLOCK) * (MapSquareTiles.SIZE / BLOCK)
    }
}

/** An opcode with no payload at all. */
class MapSquareUnknown130 : MapSquareEffect {

    override val opcode: Int
        get() = OPCODE

    companion object {
        const val OPCODE = 0x82
    }
}
