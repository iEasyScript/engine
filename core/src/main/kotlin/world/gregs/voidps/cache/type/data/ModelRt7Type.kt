package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * One RuneTek 7 model, as `re-resources/docs/cache/rt7-model-format.md` describes it.
 *
 * Fields whose role that document leaves unproven carry positional names; every byte of the file is
 * one of them, so the encoder writes the model back without keeping any of its bytes.
 */
class ModelRt7Type(
    override var id: Int = -1,
    var version: Int = 0,
    var format: Int = MODERN_FORMAT,
    var fieldOpaque: Int = 0,
    var vertexFlags: Int = 0,
    var vertices: ModelRt7Vertices = ModelRt7Vertices(),
    var submeshes: MutableList<ModelRt7Submesh> = ArrayList(),
    var attachmentsA: MutableList<ModelRt7AttachmentA> = ArrayList(),
    var attachmentsB: MutableList<ModelRt7AttachmentB> = ArrayList(),
    var attachmentsC: MutableList<ModelRt7AttachmentC> = ArrayList(),
    var attachmentsD: MutableList<ModelRt7AttachmentD> = ArrayList()
) : CacheType {

    val floatPositions: Boolean
        get() = vertexFlags and FLOAT_POSITIONS != 0

    val skinned: Boolean
        get() = vertexFlags and SKINS != 0

    val wideIndices: Boolean
        get() = vertices.count >= WIDE_INDEX_VERTICES

    companion object {
        /** The only format this decoder reads; a lower one is the client's dead legacy path. */
        const val MODERN_FORMAT = 5

        const val STREAM_B = 0x1
        const val STREAM_C = 0x4
        const val STREAM_A = 0x8
        const val SKINS = 0x20

        /** Set in 611 served models and read by no client code; it changes no layout. */
        const val UNUSED = 0x40

        const val FLOAT_POSITIONS = 0x100

        /** From here up a submesh's indices are `u32` big-endian rather than `u16` little-endian. */
        const val WIDE_INDEX_VERTICES = 65536

        /** The index count is a `u16`, so a submesh cannot hold more than this many indices. */
        const val MAX_INDICES = 0xffff
    }
}

/**
 * The vertex block: parallel plane-major streams, never interleaved.
 *
 * [positions] is three floats per vertex whether the file stored `i16` or `f32`, which is lossless
 * either way; [vertexFlags][ModelRt7Type.vertexFlags] says which the encoder writes back.
 */
class ModelRt7Vertices(
    var count: Int = 0,
    /** x, y, z per vertex. */
    var positions: FloatArray = FloatArray(0),
    /** x, y, z per vertex, a unit vector scaled by 127. */
    var normals: ByteArray = ByteArray(0),
    /** Four bytes per vertex; the fourth is 127 on an opaque vertex. */
    var colours: ByteArray = ByteArray(0),
    /** u, v per vertex. */
    var textures: ShortArray = ShortArray(0),
    var streamA: ShortArray? = null,
    var streamB: ShortArray? = null,
    var streamC: ShortArray? = null,
    /** The unconditional per-vertex byte, which sits between [streamB] and [streamC]. */
    var byteStream: ByteArray = ByteArray(0),
    /** Bone or label indices per vertex, independent in length from [weights]. */
    var bones: Array<ShortArray>? = null,
    var weights: Array<ByteArray>? = null
)

class ModelRt7Submesh(
    var flags: Int = 0,
    var fieldA: Int = 0,
    var fieldB: Int = 0,
    var fieldC: Int = 0,
    var indices: IntArray = IntArray(0)
)

class ModelRt7AttachmentA(
    var fieldA: Int = 0,
    var fieldB: Int = 0,
    var fieldC: Int = 0,
    var records: MutableList<ModelRt7AttachmentRecord> = ArrayList()
)

class ModelRt7AttachmentRecord(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var fieldD: Float = 0f,
    var fieldE: Float = 0f,
    var fieldF: Int = 0,
    var fieldG: Int = 0,
    /** Four ids; all-ones is the "none" sentinel and costs the same two bytes. */
    var ids: IntArray = IntArray(IDS),
    var fieldH: Int = 0
) {
    companion object {
        const val IDS = 4
    }
}

class ModelRt7AttachmentB(
    var id: Int = 0,
    var points: Array<ModelRt7Point> = Array(POINTS) { ModelRt7Point() }
) {
    companion object {
        const val POINTS = 3
    }
}

class ModelRt7AttachmentC(
    var id: Int = 0,
    var point: ModelRt7Point = ModelRt7Point()
)

class ModelRt7Point(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var fieldA: Int = 0,
    var fieldB: Int = 0
)

/**
 * Unexercised: no served group counts one, so the record's own fields are unproven and it is carried
 * as the bytes it occupies rather than split into guesses.
 */
class ModelRt7AttachmentD(
    var tag: Int = 0,
    var name: String? = null,
    var record: ByteArray = ByteArray(RECORD_SIZE)
) {
    companion object {
        const val RECORD_SIZE = 30

        const val NAMED_TAG = 0
    }
}
