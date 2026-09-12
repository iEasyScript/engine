package world.gregs.voidps.cache.type.decoder

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.read.Reader
import world.gregs.voidps.cache.Index.VORBIS
import world.gregs.voidps.cache.TypeDecoder
import world.gregs.voidps.cache.type.data.AudioChunk
import world.gregs.voidps.cache.type.data.AudioStreamType
import world.gregs.voidps.cache.type.data.AudioStreamType.Companion.INLINE
import world.gregs.voidps.cache.type.data.AudioStreamType.Companion.MAGIC

class AudioStreamDecoder : TypeDecoder<AudioStreamType>(VORBIS) {

    override fun create(size: Int) = Array(size) { AudioStreamType(it) }

    override fun getFile(id: Int) = 0

    override fun readLoop(definition: AudioStreamType, buffer: Reader) {
        recordDecode(definition.id, buffer) { definition.decode(buffer) }
    }

    override fun AudioStreamType.read(opcode: Int, buffer: Reader) = Unit

    fun decode(id: Int, data: ByteArray): AudioStreamType =
        AudioStreamType(id).also { it.decode(BufferReader(data)) }

    private fun AudioStreamType.decode(buffer: Reader) {
        val magic = ByteArray(MAGIC.length).also { buffer.readBytes(it) }
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "Audio stream $id is not a $MAGIC container." }
        version = buffer.readInt()
        sampleCount = buffer.readInt()
        sampleRate = buffer.readInt()
        channels = buffer.readInt()
        chunks = List(buffer.readInt()) { AudioChunk(buffer.readInt(), buffer.readInt()) }
        inlineChunks = chunks.filter { it.group == INLINE }
            .map { ByteArray(it.length).also { bytes -> buffer.readBytes(bytes) } }
    }

    companion object {
        /** Whether [data] is a stream header rather than one of the bare Ogg chunk groups. */
        fun isStream(data: ByteArray): Boolean {
            if (data.size < MAGIC.length) {
                return false
            }
            return MAGIC.indices.all { (data[it].toInt() and 0xff) == MAGIC[it].code }
        }
    }
}
