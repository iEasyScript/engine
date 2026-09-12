package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * An index whose archives are named things with a codec each, and whose file ids are ids in their
 * own right.
 *
 * [ArchiveDirectoryCodec] passes the archive id through to its sub-codec, which a
 * [DefinitionLayout.Flat] codec folds into the definition id; here the sub-codec is handed archive
 * 0, so a file id is the whole definition id - an `ui_anim` id is what the play opcode takes and
 * must not carry its group id in it.
 */
class NamedArchiveCodec(
    override val id: String,
    private val names: Map<Int, String>,
    private val codecs: Map<Int, SourceCodec>
) : SourceCodec {

    override fun exact(archive: Int): Boolean = codec(archive).exact(FLAT_ARCHIVE)

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val name = names.getValue(archive.archive)
        val unpacked = codec(archive.archive).unpack(directory.resolve(name), flattened(archive))
        return UnpackedArchive(unpacked.files.map { SourceFile("$name/${it.path}", it.bytes) }, unpacked.pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive =
        codec(archive).pack(directory.resolve(names.getValue(archive)), FLAT_ARCHIVE, metadata)

    override fun archiveOf(path: String): Int? {
        val editable = SourceFiles.guarded(path) ?: path
        val separator = editable.indexOf('/')
        if (separator <= 0) {
            return null
        }
        val name = editable.substring(0, separator)
        return names.entries.firstOrNull { it.value == name }?.key
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val folder = directory.resolve(names.getValue(archive))
        if (!Files.isDirectory(folder)) {
            return emptyList()
        }
        return codec(archive).files(folder, FLAT_ARCHIVE)
    }

    private fun codec(archive: Int) = codecs.getValue(archive)

    private fun flattened(archive: SourceArchive) = SourceArchive(
        archive.index,
        FLAT_ARCHIVE,
        archive.nameHash,
        archive.fileIds,
        archive.group,
        archive.files,
        archive.chunks,
        archive.metadata
    )

    private companion object {
        const val FLAT_ARCHIVE = 0
    }
}
