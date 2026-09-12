package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileEntry
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** One archive rebuilt from the tree: the container bytes and the table entry describing them. */
class PackedContainer(
    /** The container as the target store holds it, trailer included when the store keeps one. */
    val bytes: ByteArray,
    val entry: ArchiveEntry
)

/**
 * Turning a source tree's files back into containers and reference tables.
 *
 * Shared by [Packer], [Builder] and [Verifier] so that a full pack, an incremental rebuild and a
 * parity check all produce the same bytes by construction rather than by three implementations
 * agreeing. Everything derivable is derived here and nowhere else.
 */
object SourcePack {

    /**
     * Rebuild [archive] from [directory].
     *
     * Compression, its settings, version and keys all come from [metadata]; the group comes from the
     * codec, except for an opaque archive, whose container was never opened and is stored whole, and
     * an archive whose compressed payload nothing here reproduces, which is emitted from its sidecar
     * while the group still hashes to what the sidecar was kept for. The CRC and, where the table
     * needs it, the whirlpool cover the container without its version trailer, which is the range
     * the client checks.
     *
     * @param trailers whether the target store keeps the two byte version trailer on archive containers
     */
    fun container(
        directory: Path,
        archive: Int,
        metadata: ArchiveMetadata,
        codec: SourceCodec,
        table: IndexMetadata,
        trailers: Boolean
    ): PackedContainer {
        val trailer = if (trailers) metadata.version and 0xffff else null
        val entry = ArchiveEntry(archive)
        entry.nameHash = metadata.nameHash
        entry.version = metadata.version
        val bytes: ByteArray
        val fileIds: IntArray
        if (metadata.opaque) {
            val file = directory.resolve("$archive${SourceTree.OPAQUE_EXTENSION}")
            if (!Files.isRegularFile(file)) {
                throw IOException("Opaque archive $archive has no container file at ${file.toAbsolutePath()}.")
            }
            val stored = Files.readAllBytes(file)
            bytes = if (trailer == null) stored else stored.copyOf(stored.size + Container.TRAILER_SIZE).also {
                it[stored.size] = (trailer shr 8).toByte()
                it[stored.size + 1] = trailer.toByte()
            }
            fileIds = metadata.fileIds()
            val payloadEnd = stored.size
            entry.compressedLength = payloadEnd
            if (table.checksums || table.lengths) {
                // The group is unreachable, so the table keeps whatever the unpacker recorded for it.
                entry.uncompressedLength = metadata.settings.let { 0 }
            }
        } else {
            val packed = if (metadata.verbatim) verbatim(directory, archive, metadata) else codec.pack(directory, archive, metadata)
            val group = packed.group
            val container = stored(directory, archive, metadata, group, trailer)
                ?: Container.compress(group, metadata.compression, trailer, metadata.settings)
            bytes = container.encode(metadata.xtea)
            fileIds = packed.fileIds
            entry.uncompressedCrc = CRC.calculate(group, 0, group.size)
            entry.uncompressedLength = group.size
            entry.compressedLength = bytes.size - if (trailer == null) 0 else Container.TRAILER_SIZE
        }
        val length = bytes.size - if (trailer == null) 0 else Container.TRAILER_SIZE
        entry.crc = CRC.calculate(bytes, 0, length)
        if (table.whirlpool) {
            entry.whirlpool = Container.whirlpool(bytes, length)
        }
        for (id in fileIds) {
            entry.files.add(FileEntry(id, metadata.fileNames[id] ?: 0))
        }
        return PackedContainer(bytes, entry)
    }

    private fun verbatim(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val file = directory.resolve("$archive${SourceTree.VERBATIM_EXTENSION}")
        if (!Files.isRegularFile(file)) {
            throw IOException("Verbatim archive $archive has no group file at ${file.toAbsolutePath()}.")
        }
        return PackedArchive(Files.readAllBytes(file), metadata.fileIds())
    }

    /**
     * The payload sidecar's container, while the group still hashes to the guard the sidecar was
     * kept under; null once the group has been edited, or when there never was one. A sidecar whose
     * guard no longer holds is deleted rather than left to confuse the next pack.
     */
    private fun stored(directory: Path, archive: Int, metadata: ArchiveMetadata, group: ByteArray, trailer: Int?): Container? {
        val guard = metadata.payload ?: return null
        val file = directory.resolve("$archive${SourceTree.PAYLOAD_EXTENSION}")
        if (!Files.isRegularFile(file)) {
            return null
        }
        if (SourceFiles.sha256(group) != guard) {
            Files.delete(file)
            return null
        }
        return Container.stored(metadata.compression, Files.readAllBytes(file), group.size, trailer)
    }

    /** The index's reference table over [entries], which must be ascending by archive id. */
    fun table(metadata: IndexMetadata, entries: List<ArchiveEntry>): ReferenceTable {
        val table = ReferenceTable(
            metadata.format, metadata.revision, metadata.named, metadata.whirlpool, metadata.lengths, metadata.checksums
        )
        for (entry in entries) {
            table.add(entry)
        }
        return table
    }

    /** The reference table's container bytes, which carry no version trailer. */
    fun tableContainer(directory: Path, metadata: IndexMetadata, table: ReferenceTable): ByteArray {
        val encoded = table.encode()
        val guard = metadata.payload
        if (guard != null) {
            val file = directory.resolve(TABLE_PAYLOAD)
            if (Files.isRegularFile(file)) {
                if (SourceFiles.sha256(encoded) == guard) {
                    return Container.stored(metadata.compression, Files.readAllBytes(file), encoded.size, null).encode()
                }
                Files.delete(file)
            }
        }
        return Container.compress(encoded, metadata.compression, null, metadata.settings).encode()
    }

    /** The sidecar a reference table's unreproducible payload is kept in. */
    const val TABLE_PAYLOAD = "index${SourceTree.PAYLOAD_EXTENSION}"
}
