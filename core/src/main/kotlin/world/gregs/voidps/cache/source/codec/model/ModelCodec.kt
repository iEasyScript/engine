package world.gregs.voidps.cache.source.codec.model

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.UnpackedArchive
import world.gregs.voidps.cache.type.decoder.ModelRt7Decoder
import world.gregs.voidps.cache.type.encoder.ModelRt7Encoder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Index 47 as one glTF 2.0 binary per model, `models_rt7/<id>.glb`.
 *
 * A model is an archive of one file rather than a file inside one, so this is its own codec. It is a
 * `.glb` rather than JSON because a model is geometry: Blender imports and exports the format
 * natively, so a model in the tree is a model an artist can open, and everything the format cannot
 * say rides in `extras.rs` - see [ModelGltf].
 *
 * The archive is decoded, written as a `.glb`, read straight back and re-encoded during the unpack,
 * and a model whose bytes do not come out identical keeps them in a `<id>.glb.pristine` sidecar
 * guarded by the SHA-256 of the `.glb`. Six shipped assets need that: their true index count
 * overflowed the file's own 16 bit field, so the high bits are stored nowhere and no writer can put
 * them back - `re-resources/docs/cache/rt7-model-format.md` measures them.
 */
object ModelCodec : SourceCodec {

    const val EXTENSION = ".glb"

    override val id: String
        get() = "gltf-model-rt7"

    /** A `.glb` is not the shipped bytes, so the unpacker packs the tree back and proves it anyway. */
    override fun exact(archive: Int): Boolean = false

    /** One file per archive, and its id is not in the file's name. */
    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) {
            "Index ${archive.index} archive ${archive.archive} has ${archive.files.size} files; " +
                "$id writes one model per archive."
        }
        val data = archive.files[0]
        val name = "${archive.archive}$EXTENSION"
        val glb = ModelGltf.write(ModelRt7Decoder.decode(archive.archive, data))
        if (runCatching { compile(glb, archive.archive) }.getOrNull().contentEquals(data)) {
            return UnpackedArchive(listOf(SourceFile(name, glb)))
        }
        return UnpackedArchive(
            listOf(SourceFile(name, glb), SourceFile(SourceFiles.pristine(name), data)),
            mapOf(archive.fileIds[0] to SourceFiles.sha256(glb))
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val path = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(path)) {
            throw IOException("Model $archive has no glb at ${path.toAbsolutePath()}.")
        }
        val ids = metadata.fileIds()
        check(ids.size == 1) { "Model $archive is one file on disk but its metadata lists ${ids.size}." }
        val glb = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine[ids[0]]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(glb) == hash) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            // The edit is what the cache is built from now, so the stale guard goes rather than
            // being left to confuse the next reader.
            Files.delete(sidecar)
        }
        return PackedArchive(compile(glb, archive), ids)
    }

    override fun archiveOf(path: String): Int? {
        val editable = SourceFiles.guarded(path) ?: path
        if (!editable.endsWith(EXTENSION) || editable.contains('/')) {
            return null
        }
        return editable.dropLast(EXTENSION.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val path = directory.resolve("$archive$EXTENSION")
        if (!Files.isRegularFile(path)) {
            return emptyList()
        }
        val sidecar = SourceFiles.pristine(path)
        return if (Files.isRegularFile(sidecar)) listOf(path, sidecar) else listOf(path)
    }

    /** [glb] read back into a model and encoded, which is what a pack emits. */
    private fun compile(glb: ByteArray, archive: Int): ByteArray = encoder.encode(ModelGltf.read(glb, archive))

    private val encoder = ModelRt7Encoder()
}
