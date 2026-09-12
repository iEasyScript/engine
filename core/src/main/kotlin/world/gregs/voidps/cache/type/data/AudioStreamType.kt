package world.gregs.voidps.cache.type.data

import world.gregs.voidps.cache.CacheType

/**
 * One entry of a stream's chunk table.
 *
 * [group] names where the chunk's bytes are: zero means it is stored inline, after the table, in
 * table order; anything else means the chunk is that group of the same index and nothing else.
 */
data class AudioChunk(
    var length: Int = 0,
    var group: Int = 0,
)

/**
 * A `JAGA` audio stream header: the whole logical stream's shape and the table of the Ogg Vorbis
 * chunks it is cut into.
 *
 * Each chunk is a complete, self contained Ogg Vorbis bitstream with its own identification,
 * comment and setup headers - nothing is shared between chunks or between streams. [inlineChunks]
 * holds the bytes of the chunks whose [AudioChunk.group] is zero, in table order.
 */
data class AudioStreamType(
    override var id: Int = -1,
    var version: Int = 0,
    var sampleCount: Int = 0,
    var sampleRate: Int = 0,
    var channels: Int = 0,
    var chunks: List<AudioChunk> = emptyList(),
    var inlineChunks: List<ByteArray> = emptyList(),
) : CacheType {

    companion object {
        const val MAGIC = "JAGA"

        const val INLINE = 0
    }
}
