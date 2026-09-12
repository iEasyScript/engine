package world.gregs.voidps.cache.type.encoder

import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.TypeEncoder
import world.gregs.voidps.cache.type.data.AudioStreamType
import world.gregs.voidps.cache.type.data.AudioStreamType.Companion.MAGIC

class AudioStreamEncoder : TypeEncoder<AudioStreamType> {

    override fun Writer.encode(definition: AudioStreamType) {
        writeBytes(MAGIC.toByteArray(Charsets.US_ASCII))
        writeInt(definition.version)
        writeInt(definition.sampleCount)
        writeInt(definition.sampleRate)
        writeInt(definition.channels)
        writeInt(definition.chunks.size)
        for (chunk in definition.chunks) {
            writeInt(chunk.length)
            writeInt(chunk.group)
        }
        for (bytes in definition.inlineChunks) {
            writeBytes(bytes)
        }
    }
}
