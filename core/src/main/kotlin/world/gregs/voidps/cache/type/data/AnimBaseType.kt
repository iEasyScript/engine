package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * A skeleton: the transform list an [SkeletalAnimType] frame edits and the bind pose an
 * [KeyframeAnimType] track addresses. `re-resources/docs/cache/skeletal-animation-formats.md` is the format.
 */
class AnimBaseType(
    override var id: Int = -1,
    var version: Int = DEFAULT_VERSION,
    var transforms: MutableList<AnimBaseTransform> = ArrayList(),
    var matrixSetCount: Int = 0,
    var bones: MutableList<AnimBaseBone> = ArrayList(),
    /** Signed: a negative count carries no [remaps] at all. */
    var remapCount: Int = 0,
    var remaps: IntArray = IntArray(0),
    /** Read whether or not the version carries [extras], so it is stored rather than derived. */
    var extraCount: Int = 0,
    var extras: MutableList<AnimBaseExtra> = ArrayList()
) : CacheType {

    companion object {
        /** The version a file that skips the extended header implies. */
        const val DEFAULT_VERSION = 4

        /** A bone label or remap entry that binds to nothing; stored, not folded to 0. */
        const val NONE = 0xffff
    }
}

/** One animation transform: its kind, the model labels it moves, and the two fields nothing parses. */
class AnimBaseTransform(
    val type: Int,
    val unusedA: Int,
    val unusedB: Int,
    val labels: IntArray
)

/** One bone: the label it binds to and [AnimBaseType.matrixSetCount] sets of [MATRIX_FLOATS] floats. */
class AnimBaseBone(
    val label: Int,
    val matrices: FloatArray
) {
    companion object {
        /** A 4x4 row-major bind pose and a three float suffix, per matrix set. */
        const val MATRIX_FLOATS = 19
    }
}

/** A named float attachment; never populated in the served cache. */
class AnimBaseExtra(
    val name: String,
    val key: Int,
    val b: Int,
    val values: FloatArray
) {
    companion object {
        const val FLOATS = 7
    }
}
