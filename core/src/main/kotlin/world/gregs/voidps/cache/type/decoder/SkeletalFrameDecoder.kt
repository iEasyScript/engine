package world.gregs.voidps.cache.type.decoder

import it.unimi.dsi.fastutil.ints.IntArrayList
import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.type.data.AnimBaseType
import world.gregs.voidps.cache.type.data.SkeletalFrameType

/**
 * The lossless read of one multipart animation frame.
 *
 * The framebase is an input rather than a lookup: the value encoding of a transform is selected by that
 * framebase's type for it, so a frame that moves a transform its framebase does not have cannot be read
 * at all and says so by throwing.
 */
object SkeletalFrameDecoder {

    fun decode(id: Int, data: ByteArray, transformTypes: IntArray): SkeletalFrameType {
        val frame = SkeletalFrameType(id)
        val buffer = BufferReader(data)
        frame.version = buffer.readUnsignedByte()
        frame.frameBaseId = buffer.readUnsignedShort()
        val transformCount = buffer.readUnsignedShort()
        frame.masks = IntArray(transformCount) { buffer.readUnsignedByte() }
        readValues(frame, buffer, transformTypes)
        require(buffer.remaining == 0) { "Frame $id left ${buffer.remaining} bytes unread." }
        return frame
    }

    private fun readValues(frame: SkeletalFrameType, buffer: Reader, transformTypes: IntArray) {
        val values = IntArrayList()
        val wide = IntArrayList()
        for ((transform, mask) in frame.masks.withIndex()) {
            if (mask and ANY_AXIS == 0) {
                continue
            }
            require(transform < transformTypes.size) {
                "Frame ${frame.id} moves transform $transform, which framebase ${frame.frameBaseId} does not have."
            }
            val paired = paired(frame.version, transformTypes[transform])
            for (axis in AXIS_BITS) {
                if (mask and axis == 0) {
                    continue
                }
                readValue(buffer, values, wide)
                if (paired) {
                    readValue(buffer, values, wide)
                }
            }
        }
        frame.values = values.toIntArray()
        frame.wide = wide.toIntArray()
    }

    private fun readValue(buffer: Reader, values: IntArrayList, wide: IntArrayList) {
        val start = buffer.position()
        val value = buffer.readSignedSmart()
        if (buffer.position() - start > 1 && value in NARROW) {
            wide.add(values.size)
        }
        values.add(value)
    }

    /** Two smarts an axis rather than one; the second is the client's to discard, not a codec's. */
    private fun paired(version: Int, type: Int): Boolean =
        type == PAIRED_TYPE || (version > 1 && type == PAIRED_DISCARDED_TYPE)

    /** The framebase's types, which is all of it a frame needs. */
    fun transformTypes(base: AnimBaseType): IntArray = IntArray(base.transforms.size) { base.transforms[it].type }

    private val AXIS_BITS = intArrayOf(SkeletalFrameType.X, SkeletalFrameType.Y, SkeletalFrameType.Z)

    /** A mask with no axis bit carries no values, so its transform's type is never consulted. */
    private const val ANY_AXIS = SkeletalFrameType.X or SkeletalFrameType.Y or SkeletalFrameType.Z

    private val NARROW = -64..63

    private const val PAIRED_TYPE = 2

    private const val PAIRED_DISCARDED_TYPE = 7
}
