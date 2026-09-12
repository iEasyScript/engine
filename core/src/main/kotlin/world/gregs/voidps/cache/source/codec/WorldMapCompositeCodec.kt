package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.type.data.WorldMapCompositeType
import world.gregs.voidps.cache.type.decoder.WorldMapCompositeDecoder
import world.gregs.voidps.cache.type.decoder.WorldMapCompositeDecoder.Companion.OVERLAY_BYTES
import world.gregs.voidps.cache.type.encoder.WorldMapCompositeEncoder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * A map area's composite: the image as a PNG and the overlay table beside it as JSON.
 *
 * The cache file is an int length, that many bytes of PNG and then a table of fixed width overlay
 * entries. Splitting it is what makes both halves editable - the image opens in anything, the table
 * is a list of named fields - and the length never reaches the tree because it is the image's own
 * size at pack time.
 *
 * The JSON is written even for the areas whose table is empty, so a file id always has the same two
 * files and packing never has to guess whether an absent table means empty or means missing.
 *
 * Flat, like [RawFilesCodec.FLAT]: the codec is used inside an [ArchiveDirectoryCodec] directory, so
 * its paths carry no archive id and it cannot answer [archiveOf].
 */
class WorldMapCompositeCodec : SourceCodec {

    override val id: String
        get() = "worldmap-composite"

    private val decoder = WorldMapCompositeDecoder()

    private val encoder = WorldMapCompositeEncoder()

    private val json by lazy { DefinitionJson.of(WorldMapCompositeType::class.java) }

    /** The JSON is not the shipped bytes, so the unpacker packs the tree back and proves it. */
    override fun exact(archive: Int): Boolean = false

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val files = ArrayList<SourceFile>(archive.files.size * 2)
        val pristine = HashMap<Int, String>(0)
        for ((position, file) in archive.fileIds.withIndex()) {
            val data = archive.files[position]
            val image = WorldMapCompositeDecoder.image(data)
            val overlays = WorldMapCompositeDecoder.overlays(data)
            val definition = WorldMapCompositeType(file)
            decoder.readLoop(definition, BufferReader(overlays))
            val text = json.write(definition)
            files.add(SourceFile(image(file), image))
            files.add(SourceFile(overlays(file), text))
            if (!encoded(definition).contentEquals(overlays)) {
                files.add(SourceFile(SourceFiles.pristine(overlays(file)), overlays))
                pristine[file] = SourceFiles.sha256(text)
            }
        }
        return UnpackedArchive(files, pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = fileIds(directory)
        check(ids.isNotEmpty()) { "Archive $archive has no composite files in ${directory.toAbsolutePath()}." }
        return PackedArchive(ArchiveGroup.join(ids.map { file(directory, it, metadata) }, null, metadata.layout, metadata.layoutVersion), ids)
    }

    override fun archiveOf(path: String): Int? = null

    override fun files(directory: Path, archive: Int): List<Path> {
        val files = ArrayList<Path>()
        for (id in fileIds(directory)) {
            files.add(directory.resolve(image(id)))
            val overlays = directory.resolve(overlays(id))
            files.add(overlays)
            val sidecar = SourceFiles.pristine(overlays)
            if (Files.isRegularFile(sidecar)) {
                files.add(sidecar)
            }
        }
        return files
    }

    /** One composite file: the length the image's own size, then the image, then the table. */
    private fun file(directory: Path, id: Int, metadata: ArchiveMetadata): ByteArray {
        val path = directory.resolve(image(id))
        if (!Files.isRegularFile(path)) {
            throw IOException("Composite $id has no image at ${path.toAbsolutePath()}.")
        }
        val image = Files.readAllBytes(path)
        val overlays = overlays(directory, id, metadata)
        val writer = BufferWriter(LENGTH_BYTES + image.size + overlays.size)
        writer.writeInt(image.size)
        writer.writeBytes(image)
        writer.writeBytes(overlays)
        return writer.toArray()
    }

    /**
     * The table's bytes: the pristine copy while the JSON beside it still hashes to what
     * `index.json` recorded, and the encoded JSON otherwise.
     */
    private fun overlays(directory: Path, id: Int, metadata: ArchiveMetadata): ByteArray {
        val path = directory.resolve(overlays(id))
        if (!Files.isRegularFile(path)) {
            throw IOException("Composite $id has no overlay table at ${path.toAbsolutePath()}.")
        }
        val text = Files.readAllBytes(path)
        val sidecar = SourceFiles.pristine(path)
        val hash = metadata.pristine[id]
        if (hash != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(text) == hash) {
                return Files.readAllBytes(sidecar)
            }
            Files.delete(sidecar)
        }
        val definition = WorldMapCompositeType(id)
        json.read(text, definition)
        return encoded(definition)
    }

    private fun encoded(definition: WorldMapCompositeType): ByteArray {
        val writer = BufferWriter(maxOf(definition.overlays.size * OVERLAY_BYTES, 1))
        with(encoder) { writer.encode(definition) }
        return writer.toArray()
    }

    /** The file ids on disk, ascending, which is the order a group stores them in. */
    fun fileIds(directory: Path): IntArray {
        if (!Files.isDirectory(directory)) {
            return IntArray(0)
        }
        Files.list(directory).use { stream ->
            return stream.map { it.name }
                .filter { it.endsWith(IMAGE_EXTENSION) }
                .map { it.dropLast(IMAGE_EXTENSION.length).toIntOrNull() }
                .filter { it != null }
                .mapToInt { it!! }
                .sorted()
                .toArray()
        }
    }

    private fun image(file: Int): String = "$file$IMAGE_EXTENSION"

    private fun overlays(file: Int): String = "$file$OVERLAYS_EXTENSION"

    private companion object {
        const val IMAGE_EXTENSION = ".png"
        const val OVERLAYS_EXTENSION = ".json"
        const val LENGTH_BYTES = 4
    }
}

/** A map area's flat map image, one whole PNG per file, kept as the bytes the cache holds. */
class WorldMapImageCodec : SourceCodec {

    override val id: String
        get() = "worldmap-image"

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val files = ArrayList<SourceFile>(archive.files.size)
        for ((position, file) in archive.fileIds.withIndex()) {
            files.add(SourceFile("$file$EXTENSION", archive.files[position]))
        }
        return UnpackedArchive(files)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val ids = fileIds(directory)
        check(ids.isNotEmpty()) { "Archive $archive has no images in ${directory.toAbsolutePath()}." }
        val files = ids.map { Files.readAllBytes(directory.resolve("$it$EXTENSION")) }
        return PackedArchive(ArchiveGroup.join(files, null, metadata.layout, metadata.layoutVersion), ids)
    }

    override fun archiveOf(path: String): Int? = null

    override fun files(directory: Path, archive: Int): List<Path> =
        fileIds(directory).map { directory.resolve("$it$EXTENSION") }

    fun fileIds(directory: Path): IntArray {
        if (!Files.isDirectory(directory)) {
            return IntArray(0)
        }
        Files.list(directory).use { stream ->
            return stream.map { it.name }
                .filter { it.endsWith(EXTENSION) }
                .map { it.dropLast(EXTENSION.length).toIntOrNull() }
                .filter { it != null }
                .mapToInt { it!! }
                .sorted()
                .toArray()
        }
    }

    private companion object {
        const val EXTENSION = ".png"
    }
}
