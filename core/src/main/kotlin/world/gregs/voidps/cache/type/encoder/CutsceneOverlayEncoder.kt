package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.CutsceneElement
import world.gregs.voidps.cache.type.data.CutsceneOverlayType
import world.gregs.voidps.cache.type.data.CutsceneScalarKey
import world.gregs.voidps.cache.type.data.CutsceneTrack
import world.gregs.voidps.cache.type.data.CutsceneVectorKey
import world.gregs.voidps.cache.type.writeVersionedRawString

class CutsceneOverlayEncoder : TypeEncoder<CutsceneOverlayType> {

    override fun Writer.encode(definition: CutsceneOverlayType) {
        writeByte(definition.version)
        writeShort(definition.width)
        writeShort(definition.height)
        writeShort(definition.subtitleEnum)
        writeByte(definition.elements.size)
        for (element in definition.elements) {
            writeElement(element)
        }
        skip(definition.padding)
    }

    private fun Writer.writeElement(element: CutsceneElement) {
        writeVersionedRawString(element.name)
        writeFloat(element.start)
        writeFloat(element.end)
        writeByte(element.tracks.size)
        for (track in element.tracks) {
            writeTrack(track)
        }
        writeByte(element.sounds.size)
        for (sound in element.sounds) {
            writeVersionedRawString(sound.name)
            writeInt(sound.sound)
        }
        writeByte(element.subtitles.size)
        for (subtitle in element.subtitles) {
            writeVersionedRawString(subtitle.key)
            writeInt(subtitle.ordinal)
            writeFloat(subtitle.start)
            writeFloat(subtitle.end)
        }
    }

    private fun Writer.writeTrack(track: CutsceneTrack) {
        writeVersionedRawString(track.asset)
        writeShort(track.sourceWidth)
        writeShort(track.sourceHeight)
        writeInt(track.graphic)
        writeScalars(track.firstScalar)
        writeScalars(track.secondScalar)
        writeVectors(track.firstVector)
        writeVectors(track.secondVector)
    }

    private fun Writer.writeScalars(keys: List<CutsceneScalarKey>) {
        writeByte(keys.size)
        for (key in keys) {
            writeFloat(key.time)
            writeFloat(key.value)
        }
    }

    private fun Writer.writeVectors(keys: List<CutsceneVectorKey>) {
        writeByte(keys.size)
        for (key in keys) {
            writeFloat(key.time)
            writeFloat(key.x)
            writeFloat(key.y)
        }
    }
}
