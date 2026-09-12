package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bytes, laid out by whatever shape the archive has: `<archive><extension>` for an archive of one
 * file and `<archive>/<file>.dat` for one of several.
 *
 * The codec every index starts with, and the one an index nothing has decoded keeps, so it has to
 * cope with an index whose archives are not all the same shape without being told anything. The
 * single file's id goes in the metadata when it is not 0 (the file name cannot carry both ids);
 * the files of a multi-file archive carry their own ids in their names, so adding a file is adding
 * a file. A multi-file group is joined back in the layout it was split from, which the metadata
 * records when it is not the wire format.
 *
 * [extension] names the format of a single-file archive when the index's is known - `.ogg` says
 * more than `.dat` - and [extensionOf] lets an index whose archives are not all one format pick
 * by content, which is what the audio stream index needs.
 */
class RawCodec(
    override val id: String = "raw",
    private val extension: String = DEFAULT_EXTENSION,
    /** The extension for one particular single-file archive, from its bytes; null for [extension]. */
    private val extensionOf: (ByteArray) -> String? = { null }
) : SourceCodec {

    private val extensions: Set<String>
        get() = knownExtensions

    @Volatile
    private var knownExtensions: Set<String> = setOf(extension, DEFAULT_EXTENSION)

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        if (archive.files.size == 1) {
            val bytes = archive.files[0]
            val chosen = extensionOf(bytes) ?: extension
            if (chosen !in knownExtensions) {
                knownExtensions = knownExtensions + chosen
            }
            return UnpackedArchive(listOf(SourceFile("${archive.archive}$chosen", bytes)))
        }
        val files = ArrayList<SourceFile>(archive.files.size)
        for ((position, id) in archive.fileIds.withIndex()) {
            files.add(SourceFile("${archive.archive}/$id$DEFAULT_EXTENSION", archive.files[position]))
        }
        return UnpackedArchive(files)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val folder = directory.resolve(archive.toString())
        if (Files.isDirectory(folder)) {
            val ids = fileIds(folder)
            check(ids.isNotEmpty()) { "Archive $archive has no files in ${folder.toAbsolutePath()}." }
            val files = ids.map { Files.readAllBytes(folder.resolve("$it$DEFAULT_EXTENSION")) }
            val chunks = if (metadata.fileCount == ids.size && metadata.files == null) null else null
            return PackedArchive(ArchiveGroup.join(files, chunks, metadata.layout, metadata.layoutVersion), ids)
        }
        val file = single(directory, archive)
            ?: throw IOException("Archive $archive has no data file in ${directory.toAbsolutePath()}.")
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Archive $archive is one file on disk but its metadata lists ${ids.size}." }
        return PackedArchive(Files.readAllBytes(file), ids)
    }

    override fun archiveOf(path: String): Int? {
        val separator = path.indexOf('/')
        if (separator < 0) {
            val dot = path.lastIndexOf('.')
            if (dot <= 0) {
                return null
            }
            return path.substring(0, dot).toIntOrNull()
        }
        if (path.indexOf('/', separator + 1) >= 0 || !path.endsWith(DEFAULT_EXTENSION)) {
            return null
        }
        if (path.substring(separator + 1).dropLast(DEFAULT_EXTENSION.length).toIntOrNull() == null) {
            return null
        }
        return path.substring(0, separator).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = directory.resolve(archive.toString())
        if (Files.isDirectory(folder)) {
            return fileIds(folder).map { folder.resolve("$it$DEFAULT_EXTENSION") }
        }
        val file = single(directory, archive) ?: return emptyList()
        return listOf(file)
    }

    /** The one file of a single-file archive, whichever extension it was written with. */
    private fun single(directory: Path, archive: Int): Path? {
        for (extension in extensions) {
            val file = directory.resolve("$archive$extension")
            if (Files.isRegularFile(file)) {
                return file
            }
        }
        if (!Files.isDirectory(directory)) {
            return null
        }
        val prefix = "$archive."
        Files.list(directory).use { stream ->
            return stream.filter { path ->
                val name = path.fileName.toString()
                name.startsWith(prefix) && name.substring(prefix.length).all { it.isLetterOrDigit() } && Files.isRegularFile(path)
            }.findFirst().orElse(null)
        }
    }

    private fun fileIds(folder: Path): IntArray {
        val ids = ArrayList<Int>()
        Files.list(folder).use { stream ->
            for (path in stream) {
                val name = path.fileName.toString()
                if (name.endsWith(DEFAULT_EXTENSION)) {
                    name.dropLast(DEFAULT_EXTENSION.length).toIntOrNull()?.let { ids.add(it) }
                }
            }
        }
        ids.sort()
        return ids.toIntArray()
    }

    companion object {
        const val DEFAULT_EXTENSION = ".dat"

        /** The default: `.dat` either way. */
        val DEFAULT = RawCodec()

        /** A codec whose single-file archives say what they are. */
        fun typed(id: String, extension: String): RawCodec = RawCodec(id, extension)

        /** A codec whose single-file archives are named by their leading bytes. */
        fun sniffed(id: String, fallback: String, magic: Map<String, String>): RawCodec = RawCodec(id, fallback) { bytes ->
            magic.entries.firstOrNull { (prefix, _) -> startsWith(bytes, prefix) }?.value
        }

        private fun startsWith(bytes: ByteArray, magic: String): Boolean {
            if (bytes.size < magic.length) {
                return false
            }
            for (index in magic.indices) {
                if ((bytes[index].toInt() and 0xff) != magic[index].code) {
                    return false
                }
            }
            return true
        }
    }
}
