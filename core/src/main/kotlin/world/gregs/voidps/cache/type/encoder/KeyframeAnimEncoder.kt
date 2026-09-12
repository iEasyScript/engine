package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.KeyframeAnimType

/** The keyframed animation decoder backwards, byte for byte. */
class KeyframeAnimEncoder : TypeEncoder<KeyframeAnimType> {

    override fun Writer.encode(definition: KeyframeAnimType) {
        writeByte(definition.version)
        writeShort(definition.frameBaseId)
        writeShort(definition.unknownA)
        writeShort(definition.duration)
        writeByte(definition.unknownC)
        writeShort(definition.tracks.size)
        for (track in definition.tracks) {
            writeByte(track.kind)
            if (track.wideChannel) {
                writeShort(track.channelIndex - 0x4000 and 0xffff)
            } else {
                writeSignedSmart(track.channelIndex)
            }
            writeByte(track.curveType)
            writeShort(track.keys.size)
            writeByte(track.curveKind)
            writeByte(track.unusedD)
            writeByte(track.unusedE)
            writeByte(track.flag)
            for (key in track.keys) {
                writeShort(key.time)
                writeFloat(key.value)
                writeFloat(key.inTangentX)
                writeFloat(key.inTangentY)
                writeFloat(key.outTangentX)
                writeFloat(key.outTangentY)
            }
        }
    }
}
