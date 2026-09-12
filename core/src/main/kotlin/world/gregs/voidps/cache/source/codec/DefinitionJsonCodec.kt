package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.CacheType
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A definition index as one pretty-printed JSON file per definition.
 *
 * The whole point of the source tree is that a loc, an obj or an interface component is a file somebody
 * can open, read and change, and this is the codec that makes them one. It is only usable for a type whose
 * encoder reproduces the shipped file exactly - `docs/cache-source.md`, "Definition encoders", is the
 * measurement - because parity is not negotiable: every file is decoded, written as JSON, read back and
 * re-encoded during the unpack, and any file whose bytes do not come out identical keeps its original bytes
 * in a `<name>.pristine` sidecar guarded by the SHA-256 of the JSON, exactly as the raw codecs' pristine
 * mechanism does. So the tree reproduces the cache whether or not the encoder is perfect, and the pristine
 * count in `cache status` is the honest measure of how perfect it is.
 *
 * Decoding goes through the decoder's read path and nothing else (`readLoop` for an opcode keyed type),
 * never `load`: `changeValues` derives values that are not in the file - an obj's note template, an
 * overlay's slot - and a definition re-encoded after it no longer matches its file. A decoder that takes a
 * [world.gregs.voidps.cache.definition.Parameters] catalog must be built with the default
 * [world.gregs.voidps.cache.definition.Parameters.EMPTY], because a named parameter cannot be turned back
 * into the three byte key the file needs.
 *
 * A type with no encoder yet is still stored as JSON - the readable form is the point - but every
 * file keeps its sidecar, and packing an edited one fails rather than guessing.
 *
 * @param layout where the files go and how an archive and a file id make a definition id
 * @param create a fresh definition of the given id, which is what both directions decode into
 * @param decode the decoder's read path
 * @param encode the encoder, or null while the type has none
 * @param gameval the catalog that names this type's ids, written beside each id for the reader
 * @param catalogName the name for a type whose catalog is keyed by more than the definition id -
 *   a component's entry is `<interface>:<component>` - and which therefore cannot go through [gameval]
 */
class DefinitionJsonCodec<T : CacheType>(
    override val id: String,
    private val layout: DefinitionLayout,
    private val create: (Int) -> T,
    private val decode: (T, ByteArray) -> Unit,
    private val encode: ((T, Writer) -> Unit)? = null,
    private val gameval: String? = null,
    private val catalogName: ((archive: Int, file: Int) -> String?)? = null
) : SourceCodec {

    /** Derived from the first definition rather than passed in; nothing else knows the class better. */
    private val json by lazy { DefinitionJson.of(create(0)::class.java) }

    /**
     * False: a JSON file is not the shipped bytes, so the unpacker packs the tree back and proves it
     * anyway. [unpack] has already fallen back to a pristine sidecar for anything that did not survive
     * the round trip, so this is a second, independent check of the parity guarantee rather than the
     * only one.
     */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val files = ArrayList<SourceFile>(archive.files.size * 2)
        val pristine = HashMap<Int, String>(0)
        for ((position, file) in archive.fileIds.withIndex()) {
            val data = archive.files[position]
            val name = layout.file(archive.archive, file)
            val definition = decoded(archive.archive, file, data)
            val text = json.write(definition, name(archive.archive, file, definition.id))
            files.add(SourceFile(name, text))
            if (encode == null || !compile(text, archive.archive, file).contentEquals(data)) {
                // The JSON is still the editable form; the shipped bytes ride along beside it until
                // somebody edits it, which is what keeps parity independent of the encoder being exact.
                files.add(SourceFile(SourceFiles.pristine(name), data))
                pristine[file] = SourceFiles.sha256(text)
            }
        }
        return UnpackedArchive(files, pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = layout.fileIds(directory, archive)
        check(ids.isNotEmpty()) { "Archive $archive has no definition files in ${directory.toAbsolutePath()}." }
        val files = ArrayList<ByteArray>(ids.size)
        for (file in ids) {
            files.add(bytes(directory, archive, file, metadata))
        }
        return PackedArchive(ArchiveGroup.join(files, null, metadata.layout, metadata.layoutVersion), ids)
    }

    /** The gameval name of one definition, for the reader; nothing reads it back. */
    private fun name(archive: Int, file: Int, definitionId: Int): String? {
        catalogName?.let { return it(archive, file) }
        val type = gameval ?: return null
        return SourceCodecs.context.gamevals.name(type, definitionId)
    }

    override fun archiveOf(path: String): Int? = layout.archiveOf(editable(path))

    override fun files(directory: Path, archive: Int): List<Path> {
        val files = ArrayList<Path>()
        for (file in layout.fileIds(directory, archive)) {
            val path = directory.resolve(layout.file(archive, file))
            files.add(path)
            val sidecar = SourceFiles.pristine(path)
            if (Files.isRegularFile(sidecar)) {
                files.add(sidecar)
            }
        }
        return files
    }

    /**
     * One file's bytes: the pristine copy while the JSON beside it still hashes to what `index.json`
     * recorded, and the compiled JSON otherwise.
     *
     * A sidecar whose JSON has moved on is deleted here rather than left to confuse the next reader: the
     * edit is what the cache is being built from now, and `cache status` counts the sidecars it is still
     * waiting on.
     */
    private fun bytes(directory: Path, archive: Int, file: Int, metadata: ArchiveMetadata): ByteArray {
        val path = directory.resolve(layout.file(archive, file))
        if (!Files.isRegularFile(path)) {
            throw IOException("Archive $archive file $file has no JSON at ${path.toAbsolutePath()}.")
        }
        val text = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine[file]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(text) == hash) {
                return Files.readAllBytes(sidecar)
            }
            Files.delete(sidecar)
        }
        return compile(text, archive, file)
    }

    /** [data] decoded into a fresh definition of the right id, through the read path alone. */
    private fun decoded(archive: Int, file: Int, data: ByteArray): T {
        val definition = create(layout.definition(archive, file))
        decode(definition, data)
        return definition
    }

    /** [text] read back into a fresh definition and encoded, which is what a pack emits. */
    private fun compile(text: ByteArray, archive: Int, file: Int): ByteArray {
        val encode = encode ?: throw IOException(
            "Archive $archive file $file was edited but $id has no encoder; restore its pristine sidecar or write one."
        )
        val definition = create(layout.definition(archive, file))
        json.read(text, definition)
        var capacity = CAPACITY
        while (true) {
            val writer = BufferWriter(capacity)
            try {
                encode(definition, writer)
                return writer.toArray()
            } catch (e: BufferOverflowException) {
                capacity *= 2
                check(capacity <= LIMIT) { "Definition ${definition.id} does not encode into $LIMIT bytes." }
            }
        }
    }

    /** The editable file a path names, which for a sidecar is the file it guards. */
    private fun editable(path: String): String = SourceFiles.guarded(path) ?: path

    private companion object {
        /** Big enough for nearly every definition; doubled on overflow all the same. */
        private const val CAPACITY = 16 * 1024

        private const val LIMIT = 8 * 1024 * 1024
    }
}
