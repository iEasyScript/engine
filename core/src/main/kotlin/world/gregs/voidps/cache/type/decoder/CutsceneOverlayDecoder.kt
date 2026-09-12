package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.CutsceneElement
import world.gregs.voidps.cache.type.data.CutsceneOverlayType
import world.gregs.voidps.cache.type.data.CutsceneScalarKey
import world.gregs.voidps.cache.type.data.CutsceneSound
import world.gregs.voidps.cache.type.data.CutsceneSubtitle
import world.gregs.voidps.cache.type.data.CutsceneTrack
import world.gregs.voidps.cache.type.data.CutsceneVectorKey
import world.gregs.voidps.cache.type.readVersionedRawString

class CutsceneOverlayDecoder : TypeDecoder<CutsceneOverlayType>(INDEX) {

    override fun create(size: Int) = Array(size) { CutsceneOverlayType(it) }

    override fun readLoop(definition: CutsceneOverlayType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun CutsceneOverlayType.read(opcode: Int, buffer: Reader) = Unit

    private fun CutsceneOverlayType.decode(buffer: Reader) {
        version = buffer.readUnsignedByte()
        require(version == VERSION) { "Cutscene overlay $id is version $version, not $VERSION." }
        width = buffer.readUnsignedShort()
        height = buffer.readUnsignedShort()
        subtitleEnum = buffer.readUnsignedShort()
        elements = List(buffer.readUnsignedByte()) { readElement(buffer) }
        padding = buffer.readableBytes()
        buffer.skip(padding)
    }

    private fun readElement(buffer: Reader) = CutsceneElement(
        name = buffer.readVersionedRawString(),
        start = buffer.readFloat(),
        end = buffer.readFloat(),
        tracks = List(buffer.readUnsignedByte()) { readTrack(buffer) },
        sounds = List(buffer.readUnsignedByte()) {
            CutsceneSound(buffer.readVersionedRawString(), buffer.readInt())
        },
        subtitles = List(buffer.readUnsignedByte()) {
            CutsceneSubtitle(buffer.readVersionedRawString(), buffer.readInt(), buffer.readFloat(), buffer.readFloat())
        },
    )

    private fun readTrack(buffer: Reader) = CutsceneTrack(
        asset = buffer.readVersionedRawString(),
        sourceWidth = buffer.readUnsignedShort(),
        sourceHeight = buffer.readUnsignedShort(),
        graphic = buffer.readInt(),
        firstScalar = readScalars(buffer),
        secondScalar = readScalars(buffer),
        firstVector = readVectors(buffer),
        secondVector = readVectors(buffer),
    )

    private fun readScalars(buffer: Reader) = List(buffer.readUnsignedByte()) {
        CutsceneScalarKey(buffer.readFloat(), buffer.readFloat())
    }

    private fun readVectors(buffer: Reader) = List(buffer.readUnsignedByte()) {
        CutsceneVectorKey(buffer.readFloat(), buffer.readFloat(), buffer.readFloat())
    }

    companion object {
        const val INDEX = 66

        private const val VERSION = 1
    }
}
