package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * A single skeletal animation: one buffer of keyframed curves, one curve per track.
 *
 * [frameBaseId] bounds what a track may address - a [KeyframeTrack.kind] of 1 addresses a bone of that
 * framebase and every other kind one of its transforms.
 */
class KeyframeAnimType(
    override var id: Int = -1,
    var version: Int = DEFAULT_VERSION,
    var frameBaseId: Int = -1,
    var unknownA: Int = 0,
    var duration: Int = 0,
    var unknownC: Int = 0,
    var tracks: MutableList<KeyframeTrack> = ArrayList()
) : CacheType {

    companion object {
        const val DEFAULT_VERSION = 1
    }
}

/**
 * One curve and the channel it drives.
 *
 * [channelIndex] is a bone or a transform of the framebase depending on [kind]; [curveType] is the
 * 1-based channel slot within the track group and [curveKind] the group's transform kind.
 */
class KeyframeTrack(
    val kind: Int,
    val channelIndex: Int,
    val curveType: Int,
    val curveKind: Int,
    val unusedD: Int,
    val unusedE: Int,
    val flag: Int,
    val keys: List<Keyframe>,
    /** Whether [channelIndex] was written in the two byte form though one byte would have held it. */
    val wideChannel: Boolean = false
)

/** One key: a value at a time, with an in and an out tangent vector. */
class Keyframe(
    val time: Int,
    val value: Float,
    val inTangentX: Float,
    val inTangentY: Float,
    val outTangentX: Float,
    val outTangentY: Float
)
