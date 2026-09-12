package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.Keyframe
import world.gregs.voidps.cache.type.data.KeyframeAnimType
import world.gregs.voidps.cache.type.data.KeyframeTrack

/**
 * The lossless read of a single skeletal animation: a header, then a track per curve, each of which is a
 * curve header and its keys. Positional throughout, ending exactly where the last key does.
 */
class KeyframeAnimDecoder : TypeDecoder<KeyframeAnimType>(Index.ANIMS_KEYFRAMES) {

    override fun create(size: Int) = Array(size) { KeyframeAnimType(it) }

    override fun size(cache: Cache): Int = cache.lastArchiveId(index)

    override fun getFile(id: Int) = 0

    override fun KeyframeAnimType.read(opcode: Int, buffer: Reader) = Unit

    override fun readLoop(definition: KeyframeAnimType, buffer: Reader) {
        recordDecode(definition.id, buffer) { decode(definition, buffer) }
    }

    private fun decode(definition: KeyframeAnimType, buffer: Reader) {
        definition.version = buffer.readUnsignedByte()
        definition.frameBaseId = buffer.readUnsignedShort()
        definition.unknownA = buffer.readUnsignedShort()
        definition.duration = buffer.readUnsignedShort()
        definition.unknownC = buffer.readUnsignedByte()
        val trackCount = buffer.readUnsignedShort()
        val tracks = ArrayList<KeyframeTrack>(trackCount)
        for (track in 0 until trackCount) {
            tracks.add(readTrack(buffer))
        }
        definition.tracks = tracks
    }

    private fun readTrack(buffer: Reader): KeyframeTrack {
        val kind = buffer.readUnsignedByte()
        val start = buffer.position()
        val channelIndex = buffer.readSignedSmart()
        val wideChannel = buffer.position() - start > 1 && channelIndex in NARROW
        val curveType = buffer.readUnsignedByte()
        val keyCount = buffer.readUnsignedShort()
        val curveKind = buffer.readUnsignedByte()
        val unusedD = buffer.readUnsignedByte()
        val unusedE = buffer.readUnsignedByte()
        val flag = buffer.readUnsignedByte()
        val keys = ArrayList<Keyframe>(keyCount)
        for (key in 0 until keyCount) {
            keys.add(
                Keyframe(
                    time = buffer.readUnsignedShort(),
                    value = buffer.readFloat(),
                    inTangentX = buffer.readFloat(),
                    inTangentY = buffer.readFloat(),
                    outTangentX = buffer.readFloat(),
                    outTangentY = buffer.readFloat()
                )
            )
        }
        return KeyframeTrack(kind, channelIndex, curveType, curveKind, unusedD, unusedE, flag, keys, wideChannel)
    }

    private companion object {
        private val NARROW = -64..63
    }
}
