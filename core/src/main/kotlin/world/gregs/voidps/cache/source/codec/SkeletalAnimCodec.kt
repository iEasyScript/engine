package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.SourceTree
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.type.data.SkeletalAnimType
import world.gregs.voidps.cache.type.data.SkeletalFrameType
import world.gregs.voidps.cache.type.decoder.SkeletalFrameDecoder
import world.gregs.voidps.cache.type.encoder.SkeletalFrameEncoder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Index 48 as `anims_rt7/<archive>.json`: one animation, all of its frames, keyed by the time each plays
 * at rather than by a position.
 *
 * **The three chunk group layout comes back with it.** A multi-frame group is stored in three chunks and
 * a group joined as one is different - equally valid, but different - bytes, so the split is derived from
 * the frames themselves: the header, the mask byte per transform, then the values. Nothing about it is
 * recorded, and adding or removing a frame stays an edit to one file.
 *
 * **Unpacking needs the skeletons.** Which values a frame carries for a transform is selected by that
 * transform's type in the framebase, which is index 1's file and not this one's, so the framebases are
 * read from the tree - `bases/<id>.json`, already unpacked, since indices are unpacked ascending.
 * **Packing needs nothing**: the file holds the whole value run in wire order, so a build from a tree is
 * self-sufficient. An animation whose framebase declares fewer transforms than its frames move cannot be
 * read by the format at all - `re-resources/docs/cache/skeletal-animation-formats.md` measures the 53 that
 * are like this - and keeps its group whole through the framework's verbatim mechanism.
 */
internal object SkeletalAnimCodec : SourceCodec {

    override val id: String
        get() = "skeletal-anim"

    override fun preservesChunks(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val name = name(archive.archive)
        val anim = try {
            decode(archive)
        } catch (e: Exception) {
            return verbatim(archive)
        }
        val json = SkeletalJson.writeAnim(anim)
        if (runCatching { encode(SkeletalJson.readAnim(json)) }.getOrNull().contentEquals(archive.group)) {
            return UnpackedArchive(listOf(SourceFile(name, json)))
        }
        return UnpackedArchive(
            listOf(SourceFile(name, json), SourceFile(SourceFiles.pristine(name), archive.group)),
            mapOf(archive.fileIds[0] to SourceFiles.sha256(json))
        )
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        if (metadata.verbatim) {
            return PackedArchive(read(directory.resolve(group(archive)), archive), metadata.fileIds())
        }
        val path = directory.resolve(name(archive))
        val json = read(path, archive)
        val anim = SkeletalJson.readAnim(json)
        check(anim.frames.isNotEmpty()) { "Animation $archive has no frames in ${path.toAbsolutePath()}." }
        val ids = IntArray(anim.frames.size) { anim.frames[it].id }
        val sidecar = SourceFiles.pristine(path)
        val guard = metadata.pristine.values.firstOrNull()
        if (guard != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(json) == guard) {
                return PackedArchive(Files.readAllBytes(sidecar), ids)
            }
            Files.delete(sidecar)
        }
        return PackedArchive(encode(anim), ids)
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

    /** Forget the framebases read so far, for a caller that moved on to another tree. */
    fun reset() {
        synchronized(this) {
            bases.clear()
            directory = null
        }
    }

    private fun decode(archive: SourceArchive): SkeletalAnimType {
        val anim = SkeletalAnimType(archive.archive)
        for ((position, time) in archive.fileIds.withIndex()) {
            val file = archive.files[position]
            anim.frames.add(SkeletalFrameDecoder.decode(time, file, types(file)))
        }
        return anim
    }

    /** The framebase a frame names, read out of its own header before the frame itself is decoded. */
    private fun types(file: ByteArray): IntArray {
        check(file.size >= SkeletalFrameType.HEADER_SIZE) { "A frame of ${file.size} bytes has no header." }
        val base = ((file[1].toInt() and 0xff) shl 8) or (file[2].toInt() and 0xff)
        return transformTypes(base)
    }

    private fun transformTypes(base: Int): IntArray {
        val directory = bases()
        return bases.computeIfAbsent(base) { id ->
            val path = directory.resolve("$id$EXTENSION")
            if (!Files.isRegularFile(path)) {
                throw IOException("Framebase $id is not in the tree at ${path.toAbsolutePath()}.")
            }
            SkeletalFrameDecoder.transformTypes(SkeletalJson.readBase(Files.readAllBytes(path)))
        }
    }

    private fun bases(): Path {
        val context = SourceCodecs.context
        val path = context.root.resolve(IndexNames.of(context.revision).name(Index.ANIMATION_SKELETONS))
        val current = directory
        if (current != null && current == path) {
            return current
        }
        return synchronized(this) {
            if (directory != path) {
                bases.clear()
                directory = path
            }
            path
        }
    }

    /** One animation's group: its frames encoded, joined under the split the frame format itself gives. */
    private fun encode(anim: SkeletalAnimType): ByteArray {
        val files = ArrayList<ByteArray>(anim.frames.size)
        for (frame in anim.frames) {
            files.add(SkeletalWriter.bytes(frame.masks.size + frame.values.size * 2 + SkeletalFrameType.HEADER_SIZE) {
                with(encoder) { encode(frame) }
            })
        }
        return ArchiveGroup.join(files, chunks(anim, files))
    }

    private fun chunks(anim: SkeletalAnimType, files: List<ByteArray>): List<IntArray>? {
        if (files.size == 1) {
            return null
        }
        val header = IntArray(files.size) { SkeletalFrameType.HEADER_SIZE }
        val masks = IntArray(files.size) { anim.frames[it].masks.size }
        val values = IntArray(files.size) { files[it].size - SkeletalFrameType.HEADER_SIZE - masks[it] }
        return listOf(header, masks, values)
    }

    private fun verbatim(archive: SourceArchive): UnpackedArchive {
        archive.metadata.verbatim = true
        archive.metadata.fileIds(archive.fileIds)
        return UnpackedArchive(listOf(SourceFile(group(archive.archive), archive.group)))
    }

    private fun read(path: Path, archive: Int): ByteArray {
        if (!Files.isRegularFile(path)) {
            throw IOException("Animation $archive has no file at ${path.toAbsolutePath()}.")
        }
        return Files.readAllBytes(path)
    }

    private fun name(archive: Int) = "$archive$EXTENSION"

    private fun group(archive: Int) = "$archive${SourceTree.VERBATIM_EXTENSION}"

    private const val EXTENSION = ".json"

    private val encoder = SkeletalFrameEncoder()

    private val bases = ConcurrentHashMap<Int, IntArray>()

    @Volatile
    private var directory: Path? = null
}
