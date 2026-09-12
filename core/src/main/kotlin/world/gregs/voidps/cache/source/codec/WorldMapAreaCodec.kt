package world.gregs.voidps.cache.source.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.buffer.write.BufferWriter
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.IndexNames
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.type.data.WorldMapAreaType
import world.gregs.voidps.cache.type.decoder.WorldMapAreaDecoder
import world.gregs.voidps.cache.type.decoder.WorldMapAreaElementsDecoder
import world.gregs.voidps.cache.type.encoder.WorldMapAreaEncoder
import world.gregs.voidps.cache.type.encoder.WorldMapAreaElementsEncoder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The two world map area indices, laid out under each area's own name.
 *
 * Both indices are name addressed and the name that hashes to a group is the area's internal name,
 * which the world map data index carries as the first string of its details file. So an area is a
 * directory - or a file, for the coordinate index, whose groups hold one file - called
 * `ardougne_underground` rather than `119`. The hash stays the truth exactly as [NamedBytesCodec]
 * promises: packing hashes the path back and looks it up in `index.json`, so a renamed file is an
 * unrecognised file and never a re-hashed group, and an area whose name cannot be resolved falls
 * back to its id so the index is never blocked on a name.
 *
 * The area index's second file and the coordinate index's only file are the same map element list,
 * stored twice by the cache; both are written as `elements.json` and a writer must emit both.
 */
class WorldMapAreaCodec(override val id: String, private val nested: Boolean) : SourceCodec {

    private val areas = WorldMapAreaDecoder()

    private val elements = WorldMapAreaElementsDecoder()

    private val areaEncoder = WorldMapAreaEncoder()

    private val elementsEncoder = WorldMapAreaElementsEncoder()

    /** JSON is not the shipped bytes, so the unpacker packs the tree back and proves it anyway. */
    override fun exact(archive: Int): Boolean = false

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        val name = name(directory, archive.archive, archive.metadata.nameHash)
        val files = ArrayList<SourceFile>(archive.files.size * 2)
        val pristine = HashMap<Int, String>(0)
        for ((position, file) in archive.fileIds.withIndex()) {
            val data = archive.files[position]
            val path = path(name, kind(file))
            val text = write(archive.archive, kind(file), data)
            files.add(SourceFile(path, text))
            if (!compile(archive.archive, kind(file), text).contentEquals(data)) {
                files.add(SourceFile(SourceFiles.pristine(path), data))
                pristine[file] = SourceFiles.sha256(text)
            }
        }
        return UnpackedArchive(files, pristine)
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val name = name(directory, archive, metadata.nameHash)
        val ids = metadata.fileIds()
        val files = ids.map { bytes(directory, name, archive, it, metadata) }
        return PackedArchive(ArchiveGroup.join(files, null, metadata.layout, metadata.layoutVersion), ids)
    }

    override fun archiveOf(path: String): Int? = null

    override fun archiveOf(directory: Path, path: String): Int? {
        val editable = SourceFiles.guarded(path) ?: path
        val separator = editable.indexOf('/')
        val key = if (nested) {
            if (separator <= 0 || editable.indexOf('/', separator + 1) >= 0) {
                return null
            }
            if (Kind.of(editable.substring(separator + 1)) == null) {
                return null
            }
            editable.substring(0, separator)
        } else {
            if (separator >= 0 || !editable.endsWith(JSON)) {
                return null
            }
            editable.dropLast(JSON.length)
        }
        return key.toIntOrNull() ?: ArchiveNames.archive(directory, key.hashCode())
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val name = name(directory, archive, ArchiveNames.nameHash(directory, archive) ?: return emptyList())
        val files = ArrayList<Path>(4)
        for (kind in Kind.entries) {
            val file = directory.resolve(path(name, kind))
            if (Files.isRegularFile(file)) {
                files.add(file)
                val sidecar = SourceFiles.pristine(file)
                if (Files.isRegularFile(sidecar)) {
                    files.add(sidecar)
                }
            }
        }
        return files
    }

    private fun bytes(directory: Path, name: String, archive: Int, file: Int, metadata: ArchiveMetadata): ByteArray {
        val path = directory.resolve(path(name, kind(file)))
        if (!Files.isRegularFile(path)) {
            throw IOException("Map area $archive has no ${kind(file)} file at ${path.toAbsolutePath()}.")
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
        return compile(archive, kind(file), text)
    }

    private fun write(archive: Int, kind: Kind, data: ByteArray): ByteArray = when (kind) {
        Kind.AREA -> WorldMapAreaJson.writeArea(areas.decode(archive, data))
        Kind.ELEMENTS -> WorldMapAreaJson.writeElements(elements.decode(archive, data))
    }

    private fun compile(archive: Int, kind: Kind, text: ByteArray): ByteArray {
        if (kind == Kind.ELEMENTS) {
            val list = WorldMapAreaJson.readElements(archive, text)
            val writer = BufferWriter(Short.SIZE_BYTES + list.elements.size * ELEMENT_SIZE)
            with(elementsEncoder) { writer.encode(list) }
            return writer.toArray()
        }
        val area = WorldMapAreaJson.readArea(archive, text)
        val writer = BufferWriter(capacity(area))
        with(areaEncoder) { writer.encode(area) }
        return writer.toArray()
    }

    /** An upper bound: every palette id and every cell value fits in its widest encoding. */
    private fun capacity(area: WorldMapAreaType): Int =
        2 + (area.underlays.size + area.overlays.size) * Short.SIZE_BYTES +
            area.blocks.sumOf { BLOCK_HEADER_SIZE + it.cells.size * Int.SIZE_BYTES }

    /**
     * The coordinate index's only file is the area index's second file, so the file id alone does
     * not say which kind it is - the layout does.
     */
    private fun kind(file: Int): Kind = if (nested && file == 0) Kind.AREA else Kind.ELEMENTS

    private fun path(name: String, kind: Kind): String = if (nested) "$name/${kind.file}$JSON" else "$name$JSON"

    /** [hash]'s internal name when the tree can resolve one that hashes back to it, or the id. */
    private fun name(directory: Path, archive: Int, hash: Int): String {
        val name = WorldMapAreaNames.of(directory)[archive]
        return if (name != null && name.hashCode() == hash) name else archive.toString()
    }

    private enum class Kind(val file: String) {
        AREA("area"),
        ELEMENTS("elements");

        companion object {
            fun of(file: String): Kind? = entries.firstOrNull { "${it.file}$JSON" == file }
        }
    }

    companion object {
        private const val JSON = ".json"

        private const val ELEMENT_SIZE = 7

        /** The tag, the two map square coordinates and the widest of the two record heads. */
        private const val BLOCK_HEADER_SIZE = 11

        val AREAS = WorldMapAreaCodec("worldmap-area", nested = true)

        val COORDS = WorldMapAreaCodec("worldmap-area-coords", nested = false)
    }
}

/**
 * Every map area's internal name, read out of the tree's own world map data.
 *
 * The names are cache data, not a table: the details file of each area begins with its internal
 * name, and that string is what the two area indices hash their group names from. A tree unpacked
 * without the world map data index simply has no names, and the areas fall back to their ids.
 */
private object WorldMapAreaNames {

    private val trees = ConcurrentHashMap<Path, Map<Int, String>>()

    /** The names for the tree [directory]'s index belongs to. */
    fun of(directory: Path): Map<Int, String> {
        val root = directory.parent ?: return emptyMap()
        return trees.computeIfAbsent(root) { read(it) }
    }

    private fun read(root: Path): Map<Int, String> {
        val details = root.resolve(IndexNames.of(SourceCodecs.context.revision).name(Index.WORLD_MAP))
        val names = HashMap<Int, String>()
        for (folder in listOf(details.resolve(DETAILS_NAME), details.resolve(DETAILS_ARCHIVE.toString()))) {
            if (!Files.isDirectory(folder)) {
                continue
            }
            Files.list(folder).use { stream ->
                for (file in stream) {
                    val id = id(file.fileName.toString()) ?: continue
                    name(file)?.let { names[id] = it }
                }
            }
        }
        return names
    }

    private fun id(file: String): Int? {
        val dot = file.lastIndexOf('.')
        if (dot <= 0) {
            return null
        }
        return file.substring(0, dot).toIntOrNull()
    }

    private fun name(file: Path): String? {
        val bytes = Files.readAllBytes(file)
        if (file.fileName.toString().endsWith(".json")) {
            return runCatching {
                Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject["map"]?.jsonPrimitive?.content
            }.getOrNull()
        }
        val end = bytes.indexOfFirst { it.toInt() == 0 }
        return if (end <= 0) null else String(bytes, 0, end, Charsets.US_ASCII)
    }

    /** The details archive, whichever of its two shapes the tree holds it in. */
    private const val DETAILS_ARCHIVE = 0

    private const val DETAILS_NAME = "details"
}
