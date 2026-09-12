package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.buffer.write.Writer
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.type.data.AnimBaseType
import world.gregs.voidps.cache.type.data.KeyframeAnimType
import world.gregs.voidps.cache.type.decoder.AnimBaseDecoder
import world.gregs.voidps.cache.type.decoder.KeyframeAnimDecoder
import world.gregs.voidps.cache.type.encoder.AnimBaseEncoder
import world.gregs.voidps.cache.type.encoder.KeyframeAnimEncoder
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.file.Files
import java.nio.file.Path

/**
 * An index holding one file per archive, as one JSON file per archive.
 *
 * The whole archive is the editable file, so its pristine sidecar is the archive's own bytes and the
 * guard is the SHA-256 of the JSON, exactly as a definition's is. The round trip runs during the unpack,
 * through the written JSON rather than around it, so what lands on disk always packs back to the bytes it
 * came from and the codec is exact by construction. An archive the decoder cannot read at all keeps its
 * group whole through the framework's verbatim mechanism instead, which no edit can then be made to.
 */
internal abstract class SkeletalFileCodec : SourceCodec {

    /** [file] as the JSON that replaces it, or a throw for bytes this format cannot read. */
    protected abstract fun json(archive: Int, file: ByteArray): ByteArray

    /** [json] back as the file it was written from. */
    protected abstract fun file(json: ByteArray, archive: Int): ByteArray

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val name = name(archive.archive)
        if (archive.files.size != 1) {
            return verbatim(archive)
        }
        val file = archive.files[0]
        val json = try {
            json(archive.archive, file)
        } catch (e: Exception) {
            return verbatim(archive)
        }
        if (runCatching { file(json, archive.archive) }.getOrNull().contentEquals(file)) {
            return UnpackedArchive(listOf(SourceFile(name, json)))
        }
        return UnpackedArchive(
            listOf(SourceFile(name, json), SourceFile(SourceFiles.pristine(name), file)),
            mapOf(archive.fileIds[0] to SourceFiles.sha256(json))
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = metadata.fileIds()
        if (metadata.verbatim) {
            return PackedArchive(read(directory.resolve(group(archive)), archive), ids)
        }
        val path = directory.resolve(name(archive))
        val json = read(path, archive)
        val sidecar = SourceFiles.pristine(path)
        val guard = metadata.pristine.values.firstOrNull()
        if (guard != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(json) == guard) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(file(json, archive), ids)
    }

    override fun archiveOf(path: String): Int? {
        if (path.contains('/')) {
            return null
        }
        val editable = SourceFiles.guarded(path) ?: path
        val extension = when {
            editable.endsWith(EXTENSION) -> EXTENSION
            editable.endsWith(SourceTree.VERBATIM_EXTENSION) -> SourceTree.VERBATIM_EXTENSION
            else -> return null
        }
        return editable.dropLast(extension.length).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val files = ArrayList<Path>(2)
        val path = directory.resolve(name(archive))
        if (Files.isRegularFile(path)) {
            files.add(path)
            val sidecar = SourceFiles.pristine(path)
            if (Files.isRegularFile(sidecar)) {
                files.add(sidecar)
            }
        }
        val group = directory.resolve(group(archive))
        if (Files.isRegularFile(group)) {
            files.add(group)
        }
        return files
    }

    /** The archive kept whole, for bytes this format cannot read; the framework packs it straight back. */
    private fun verbatim(archive: SourceArchive): UnpackedArchive {
        archive.metadata.verbatim = true
        archive.metadata.fileIds(archive.fileIds)
        return UnpackedArchive(listOf(SourceFile(group(archive.archive), archive.group)))
    }

    private fun read(path: Path, archive: Int): ByteArray {
        if (!Files.isRegularFile(path)) {
            throw IOException("Archive $archive has no file at ${path.toAbsolutePath()}.")
        }
        return Files.readAllBytes(path)
    }

    private fun name(archive: Int) = "$archive$EXTENSION"

    private fun group(archive: Int) = "$archive${SourceTree.VERBATIM_EXTENSION}"

    protected companion object {
        const val EXTENSION = ".json"
    }
}

/**
 * A file written into a buffer sized from its JSON and doubled until it fits.
 *
 * The JSON spells every number out, so it is longer than the bytes it came from in all but a pathological
 * case, which makes it the estimate to start from and the doubling the safety net rather than the norm.
 */
internal object SkeletalWriter {

    fun bytes(estimate: Int, write: Writer.() -> Unit): ByteArray {
        var capacity = maxOf(estimate, MINIMUM)
        while (true) {
            val writer = BufferWriter(capacity)
            try {
                writer.write()
                return writer.toArray()
            } catch (e: BufferOverflowException) {
                capacity *= 2
                check(capacity <= LIMIT) { "A skeletal archive does not encode into $LIMIT bytes." }
            }
        }
    }

    private const val MINIMUM = 1 shl 10

    private const val LIMIT = 1 shl 27
}

/** Index 1 as `bases/<id>.json`: a skeleton's transform list and bind pose. */
internal object AnimBaseCodec : SkeletalFileCodec() {

    override val id: String
        get() = "animbase"

    override fun json(archive: Int, file: ByteArray): ByteArray {
        val base = AnimBaseType(archive)
        decoder.readLoop(base, BufferReader(file))
        return SkeletalJson.writeBase(base)
    }

    override fun file(json: ByteArray, archive: Int): ByteArray {
        val base = SkeletalJson.readBase(json)
        return SkeletalWriter.bytes(json.size) { with(encoder) { encode(base) } }
    }

    private val decoder = AnimBaseDecoder()

    private val encoder = AnimBaseEncoder()
}

/** Index 56 as `anims_keyframes/<id>.json`: one animation's keyframed curves. */
internal object KeyframeAnimCodec : SkeletalFileCodec() {

    override val id: String
        get() = "keyframe-anim"

    override fun json(archive: Int, file: ByteArray): ByteArray {
        val anim = KeyframeAnimType(archive)
        decoder.readLoop(anim, BufferReader(file))
        return SkeletalJson.writeKeyframes(anim)
    }

    override fun file(json: ByteArray, archive: Int): ByteArray {
        val anim = SkeletalJson.readKeyframes(json)
        return SkeletalWriter.bytes(json.size) { with(encoder) { encode(anim) } }
    }

    private val decoder = KeyframeAnimDecoder()

    private val encoder = KeyframeAnimEncoder()
}
