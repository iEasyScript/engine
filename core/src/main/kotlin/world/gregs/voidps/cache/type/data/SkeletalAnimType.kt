package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/** A multipart skeletal animation: one archive of frames, each keyed by the time it plays at. */
class SkeletalAnimType(
    override var id: Int = -1,
    var frames: MutableList<SkeletalFrameType> = ArrayList()
) : CacheType

/**
 * One frame: a sparse edit of its framebase's transform list.
 *
 * [id] is the frame's file id in the archive, which is the time it plays at rather than its position.
 * [masks] holds one byte per transform of the framebase - the axes the frame moves and a two bit mode -
 * and [values] the whole run of signed smarts those masks call for, in transform then axis order.
 */
class SkeletalFrameType(
    override var id: Int = -1,
    var version: Int = DEFAULT_VERSION,
    var frameBaseId: Int = -1,
    var masks: IntArray = IntArray(0),
    var values: IntArray = IntArray(0),
    /** Positions in [values] the file wrote in the two byte form though one byte would have held them. */
    var wide: IntArray = IntArray(0)
) : CacheType {

    companion object {
        const val DEFAULT_VERSION = 2

        /** The version, the framebase id and the transform count, which is chunk 0 of a stored group. */
        const val HEADER_SIZE = 5

        const val X = 0x1
        const val Y = 0x2
        const val Z = 0x4
    }
}
