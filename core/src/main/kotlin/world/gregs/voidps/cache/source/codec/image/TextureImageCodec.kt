package world.gregs.voidps.cache.source.codec.image

import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.UnpackedArchive
import world.gregs.voidps.cache.source.codec.VerbatimGroupCodec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A texture index whose group is a face count and then, per face, whole image files behind their
 * own length - a mip chain of them where the index is mipped - written out as those files.
 *
 * Every byte of the framing follows from the files themselves, so the tree holds the images and
 * nothing else and packing them back is byte exact. A group framed some other way is kept whole by
 * [VerbatimGroupCodec] instead, which is what keeps parity independent of the framing being
 * understood everywhere.
 *
 * The layout is the shallowest one that can say what a group holds: `<archive><extension>` for the
 * single-face textures almost all of these indices are, `<archive>/<face>` for a cube map, and a
 * level below that where a face is a mip chain.
 */
class TextureImageCodec(
    override val id: String,
    private val extension: String,
    private val magic: ByteArray,
    /** Whether a face is a mip chain, full size first, rather than one image. */
    private val mipped: Boolean = false
) : SourceCodec {

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val faces = read(archive.group) ?: return VerbatimGroupCodec.unpack(directory, archive)
        val files = ArrayList<SourceFile>(faces.sumOf { it.size })
        for ((face, levels) in faces.withIndex()) {
            for ((level, bytes) in levels.withIndex()) {
                files.add(SourceFile(path(archive.archive, faces.size, face, level), bytes))
            }
        }
        return UnpackedArchive(files)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        if (Files.isRegularFile(directory.resolve("$archive${VerbatimGroupCodec.EXTENSION}"))) {
            return VerbatimGroupCodec.pack(directory, archive, metadata)
        }
        return PackedArchive(write(faces(directory, archive)), metadata.fileIds())
    }

    override fun archiveOf(path: String): Int? {
        VerbatimGroupCodec.archiveOf(path)?.let { return it }
        val separator = path.indexOf('/')
        if (separator < 0) {
            val dot = path.lastIndexOf('.')
            if (mipped || dot <= 0 || path.substring(dot) != extension) {
                return null
            }
            return path.substring(0, dot).toIntOrNull()
        }
        if (!path.endsWith(extension) || path.count { it == '/' } > (if (mipped) 2 else 1)) {
            return null
        }
        return path.substring(0, separator).toIntOrNull()
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val group = VerbatimGroupCodec.files(directory, archive)
        if (group.isNotEmpty()) {
            return group
        }
        val single = directory.resolve("$archive$extension")
        if (!mipped && Files.isRegularFile(single)) {
            return listOf(single)
        }
        if (!Files.isDirectory(directory.resolve(archive.toString()))) {
            return emptyList()
        }
        return faces(directory, archive).flatten()
    }

    private fun path(archive: Int, faces: Int, face: Int, level: Int): String = when {
        faces == 1 && !mipped -> "$archive$extension"
        faces == 1 -> "$archive/$level$extension"
        !mipped -> "$archive/$face$extension"
        else -> "$archive/$face/$level$extension"
    }

    /**
     * The group split into its faces, each a list of mip levels, or null when it is not framed the
     * way this index frames one.
     */
    private fun read(group: ByteArray): List<List<ByteArray>>? {
        if (group.isEmpty()) {
            return null
        }
        val count = group[0].toInt() and 0xff
        if (count == 0) {
            return null
        }
        var position = 1
        val faces = ArrayList<List<ByteArray>>(count)
        repeat(count) {
            var levels = 1
            if (mipped) {
                if (position >= group.size) {
                    return null
                }
                levels = group[position].toInt() and 0xff
                position++
                if (levels == 0) {
                    return null
                }
            }
            val images = ArrayList<ByteArray>(levels)
            repeat(levels) {
                if (position + Int.SIZE_BYTES > group.size) {
                    return null
                }
                val length = readInt(group, position)
                position += Int.SIZE_BYTES
                if (length < magic.size || length > group.size - position || !matches(group, position)) {
                    return null
                }
                images.add(group.copyOfRange(position, position + length))
                position += length
            }
            faces.add(images)
        }
        return if (position == group.size) faces else null
    }

    /** The files of each face of [archive], ascending by face and then by mip level. */
    private fun faces(directory: Path, archive: Int): List<List<Path>> {
        val single = directory.resolve("$archive$extension")
        if (!mipped && Files.isRegularFile(single)) {
            return listOf(listOf(single))
        }
        val folder = directory.resolve(archive.toString())
        if (!Files.isDirectory(folder)) {
            throw IOException("Archive $archive has no image files in ${directory.toAbsolutePath()}.")
        }
        val nested = numbered(folder, directories = true)
        if (nested.isNotEmpty()) {
            return nested.map { numbered(it, directories = false) }
        }
        val images = numbered(folder, directories = false)
        if (images.isEmpty()) {
            throw IOException("Archive $archive has no image files in ${folder.toAbsolutePath()}.")
        }
        return if (mipped) listOf(images) else images.map { listOf(it) }
    }

    /** The children of [folder] named by a number, ascending. */
    private fun numbered(folder: Path, directories: Boolean): List<Path> {
        val entries = ArrayList<Pair<Int, Path>>()
        Files.list(folder).use { stream ->
            for (path in stream) {
                val id = number(path, directories) ?: continue
                entries.add(id to path)
            }
        }
        entries.sortBy { it.first }
        return entries.map { it.second }
    }

    private fun number(path: Path, directories: Boolean): Int? {
        val name = path.fileName.toString()
        if (directories) {
            return if (Files.isDirectory(path)) name.toIntOrNull() else null
        }
        if (!Files.isRegularFile(path) || !name.endsWith(extension)) {
            return null
        }
        return name.dropLast(extension.length).toIntOrNull()
    }

    private fun write(faces: List<List<Path>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(faces.size)
        for (levels in faces) {
            if (mipped) {
                out.write(levels.size)
            }
            for (path in levels) {
                val bytes = Files.readAllBytes(path)
                writeInt(out, bytes.size)
                out.write(bytes)
            }
        }
        return out.toByteArray()
    }

    private fun matches(group: ByteArray, position: Int): Boolean {
        for (index in magic.indices) {
            if (group[position + index] != magic[index]) {
                return false
            }
        }
        return true
    }

    private fun readInt(bytes: ByteArray, position: Int): Int =
        ((bytes[position].toInt() and 0xff) shl 24) or
            ((bytes[position + 1].toInt() and 0xff) shl 16) or
            ((bytes[position + 2].toInt() and 0xff) shl 8) or
            (bytes[position + 3].toInt() and 0xff)

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value ushr 24)
        out.write(value ushr 16)
        out.write(value ushr 8)
        out.write(value)
    }
}
