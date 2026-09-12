package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.type.data.AudioChunk
import world.gregs.voidps.cache.type.data.AudioStreamType
import world.gregs.voidps.cache.type.data.AudioStreamType.Companion.INLINE
import world.gregs.voidps.cache.type.decoder.AudioStreamDecoder
import world.gregs.voidps.cache.type.encoder.AudioStreamEncoder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * An audio index as its stream headers and the Ogg Vorbis bitstreams they point at.
 *
 * Both audio indices hold the same two kinds of group and a codec has to tell them apart by
 * content: a `JAGA` stream header, which becomes `<archive>.json` describing the stream plus one
 * `<archive>/<position>.ogg` per chunk it stores inline, and a bare chunk group, which is one
 * complete Ogg bitstream and becomes `<archive>.ogg` and nothing else. A header's chunk table names
 * either an inline chunk or a sibling group of the same index, and that column is the only
 * difference between the two indices: the vorbis index stores every chunk inline, the stream index
 * stores the head inline and the rest as groups of their own.
 *
 * An inline chunk's declared length is the length of its own file and is therefore derived rather
 * than written down, exactly as the tree derives every other length; a sibling chunk's is the
 * referenced group's and is kept.
 */
class AudioStreamCodec(override val id: String) : SourceCodec {

    private val decoder = AudioStreamDecoder()

    private val encoder = AudioStreamEncoder()

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; an audio group is one."
        }
        val data = archive.files[0]
        if (!AudioStreamDecoder.isStream(data)) {
            return UnpackedArchive(listOf(SourceFile("${archive.archive}$OGG", data)))
        }
        val stream = decoder.decode(archive.archive, data)
        val files = ArrayList<SourceFile>(stream.inlineChunks.size + 1)
        files.add(SourceFile("${archive.archive}$JSON", write(stream)))
        var inline = 0
        for ((position, chunk) in stream.chunks.withIndex()) {
            if (chunk.group == INLINE) {
                files.add(SourceFile("${archive.archive}/$position$OGG", stream.inlineChunks[inline++]))
            }
        }
        return UnpackedArchive(files)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Audio archive $archive is one file on disk but its metadata lists ${ids.size}." }
        val bare = directory.resolve("$archive$OGG")
        if (Files.isRegularFile(bare)) {
            return PackedArchive(Files.readAllBytes(bare), ids)
        }
        val header = directory.resolve("$archive$JSON")
        if (!Files.isRegularFile(header)) {
            throw IOException("Audio archive $archive has no file in ${directory.toAbsolutePath()}.")
        }
        val stream = read(archive, Files.readAllBytes(header), directory.resolve(archive.toString()))
        val writer = BufferWriter(size(stream))
        with(encoder) { writer.encode(stream) }
        return PackedArchive(writer.toArray(), ids)
    }

    override fun archiveOf(path: String): Int? {
        val separator = path.indexOf('/')
        if (separator < 0) {
            val dot = path.lastIndexOf('.')
            if (dot <= 0 || (path.substring(dot) != JSON && path.substring(dot) != OGG)) {
                return null
            }
            return path.substring(0, dot).toIntOrNull()
        }
        if (path.indexOf('/', separator + 1) >= 0 || !path.endsWith(OGG)) {
            return null
        }
        if (path.substring(separator + 1).dropLast(OGG.length).toIntOrNull() == null) {
            return null
        }
        return path.substring(0, separator).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val files = ArrayList<Path>()
        for (name in listOf("$archive$OGG", "$archive$JSON")) {
            val file = directory.resolve(name)
            if (Files.isRegularFile(file)) {
                files.add(file)
            }
        }
        val folder = directory.resolve(archive.toString())
        if (Files.isDirectory(folder)) {
            Files.list(folder).use { stream -> stream.filter { Files.isRegularFile(it) }.forEach { files.add(it) } }
        }
        return files
    }

    /** The stream header, deterministic to the byte so a re-unpack leaves the tree alone. */
    private fun write(stream: AudioStreamType): ByteArray {
        val out = StringBuilder(stream.chunks.size * 32 + 128)
        out.append("{\n")
        out.append("  \"version\": ").append(stream.version).append(",\n")
        out.append("  \"sampleCount\": ").append(stream.sampleCount).append(",\n")
        out.append("  \"sampleRate\": ").append(stream.sampleRate).append(",\n")
        out.append("  \"channels\": ").append(stream.channels).append(",\n")
        out.append("  \"chunks\": [")
        for ((position, chunk) in stream.chunks.withIndex()) {
            out.append(if (position == 0) "\n" else ",\n").append("    {\"group\": ").append(chunk.group)
            if (chunk.group != INLINE) {
                out.append(", \"length\": ").append(chunk.length)
            }
            out.append('}')
        }
        if (stream.chunks.isNotEmpty()) {
            out.append("\n  ")
        }
        out.append("]\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun read(archive: Int, text: ByteArray, folder: Path): AudioStreamType {
        val root = json.parseToJsonElement(String(text, Charsets.UTF_8)).jsonObject
        val stream = AudioStreamType(archive)
        stream.version = root.getValue("version").jsonPrimitive.int
        stream.sampleCount = root.getValue("sampleCount").jsonPrimitive.int
        stream.sampleRate = root.getValue("sampleRate").jsonPrimitive.int
        stream.channels = root.getValue("channels").jsonPrimitive.int
        val chunks = ArrayList<AudioChunk>()
        val inline = ArrayList<ByteArray>()
        for ((position, element) in root.getValue("chunks").jsonArray.withIndex()) {
            val entry = element.jsonObject
            val group = entry.getValue("group").jsonPrimitive.int
            if (group != INLINE) {
                chunks.add(AudioChunk(entry.getValue("length").jsonPrimitive.int, group))
                continue
            }
            val file = folder.resolve("$position$OGG")
            if (!Files.isRegularFile(file)) {
                throw IOException("Audio archive $archive has no chunk at ${file.toAbsolutePath()}.")
            }
            val bytes = Files.readAllBytes(file)
            chunks.add(AudioChunk(bytes.size, group))
            inline.add(bytes)
        }
        stream.chunks = chunks
        stream.inlineChunks = inline
        return stream
    }

    private fun size(stream: AudioStreamType): Int =
        HEADER_SIZE + stream.chunks.size * CHUNK_ENTRY_SIZE + stream.inlineChunks.sumOf { it.size }

    private companion object {
        const val JSON = ".json"

        const val OGG = ".ogg"

        /** The magic and five big endian integers every header leads with. */
        const val HEADER_SIZE = 24

        const val CHUNK_ENTRY_SIZE = 8

        val json = Json { ignoreUnknownKeys = true }
    }
}
