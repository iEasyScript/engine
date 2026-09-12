package world.gregs.voidps.cache.source.codec.gameval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.gameval.GamevalFile
import world.gregs.voidps.cache.gameval.GamevalIndex
import world.gregs.voidps.cache.source.ArchiveMetadata
import world.gregs.voidps.cache.source.SourceFiles
import world.gregs.voidps.cache.source.SourceJson
import world.gregs.voidps.cache.source.codec.PackedArchive
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.SourceFile
import world.gregs.voidps.cache.source.codec.UnpackedArchive
import world.gregs.voidps.cache.source.gameval.GamevalCatalogs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The gameval index as the catalogs the whole toolchain reads: `gamevals/<type>.json`.
 *
 * Each archive of the index is one type's id to name table, and it is written exactly the way the
 * repository's catalogs have always been written - lower case names under `entries`, keyed by the
 * id, components keyed `<interface>:<component>` - so a tree's `gamevals/` directory *is* the
 * catalog directory everything else points at. A combined var archive is split into the domain's
 * vars and its varbits, the varbits under their own ids with the split's offset recorded so the
 * archive can be joined again.
 *
 * The client stores the names in upper case; a catalog lowers them. Packing raises them again, and
 * the unpack checks that this reproduces the archive: for the few archives it does not - a name
 * with a lower case letter in it, or a blob laid out in an order the encoder does not produce - the
 * shipped bytes ride beside the catalog as a pristine sidecar until the catalog is edited.
 */
object GamevalCodec : SourceCodec {

    override val id: String
        get() = "gameval-catalog"

    override fun exact(archive: Int): Boolean = true

    override fun fileIdsFromMetadata(archive: Int): Boolean = true

    override fun unpack(directory: Path, archive: SourceArchive): UnpackedArchive {
        check(archive.files.size == 1) { "Gameval archive ${archive.archive} has ${archive.files.size} files; one is expected." }
        val data = archive.files[0]
        val file = GamevalFile.decode(data)
        val revision = SourceCodecs.context.revision
        val files = ArrayList<SourceFile>(3)
        val domain = GamevalIndex.VAR_DOMAIN_BY_ARCHIVE[archive.archive]
        if (domain == null) {
            val type = GamevalIndex.typeName(archive.archive)
            files.add(SourceFile("$type${GamevalCatalogs.EXTENSION}", catalog(revision, archive.archive, file, type, file.names, null)))
        } else {
            val split = split(file)
            files.add(SourceFile("${domain.varType}${GamevalCatalogs.EXTENSION}", catalog(revision, archive.archive, file, domain.varType, split.vars, null)))
            if (split.varbits.isNotEmpty()) {
                files.add(SourceFile("${domain.varbitType}${GamevalCatalogs.EXTENSION}", catalog(revision, archive.archive, file, domain.varbitType, split.varbits, split.offset)))
            }
        }
        val rebuilt = runCatching { pack(files, archive.archive).group }.getOrNull()
        if (rebuilt != null && rebuilt.contentEquals(data)) {
            return UnpackedArchive(files)
        }
        val primary = files[0]
        files.add(SourceFile(SourceFiles.pristine(primary.path), data))
        return UnpackedArchive(files, mapOf(0 to SourceFiles.sha256(primary.bytes)))
    }

    override fun pack(directory: Path, archive: Int, metadata: ArchiveMetadata): PackedArchive {
        val paths = files(directory, archive).filter { !SourceFiles.isPristine(it.fileName.toString()) }
        if (paths.isEmpty()) {
            throw IOException("Gameval archive $archive has no catalog in ${directory.toAbsolutePath()}.")
        }
        val primary = paths[0]
        val guard = metadata.pristine[0]
        val sidecar = SourceFiles.pristine(primary)
        if (guard != null && Files.isRegularFile(sidecar)) {
            if (SourceFiles.sha256(primary) == guard) {
                return PackedArchive(Files.readAllBytes(sidecar), metadata.fileIds())
            }
            Files.delete(sidecar)
        }
        val sources = paths.map { SourceFile(directory.relativize(it).toString(), Files.readAllBytes(it)) }
        return pack(sources, archive)
    }

    private fun pack(files: List<SourceFile>, archive: Int): PackedArchive {
        val domain = GamevalIndex.VAR_DOMAIN_BY_ARCHIVE[archive]
        val primary = parse(files[0].bytes)
        val names = LinkedHashMap<Int, ByteArray>()
        for ((id, name) in primary.entries) {
            names[id.toInt()] = raw(name, archive)
        }
        var count = primary.count
        if (domain != null && files.size > 1) {
            val varbits = parse(files[1].bytes)
            val offset = varbits.offset ?: throw IOException("${files[1].path} records no varbit offset.")
            for ((id, name) in varbits.entries) {
                names[offset + id.toInt()] = raw(GamevalIndex.VARBIT_PREFIX + name, archive)
            }
            count = maxOf(count, varbits.count.let { if (it == 0) 0 else offset + it })
        }
        val ordered = LinkedHashMap<Int, ByteArray>(names.size * 2)
        for (id in names.keys.sorted()) {
            ordered[id] = names.getValue(id)
        }
        val version = primary.format
        val length = if (version == GamevalFile.VERSION_DENSE) maxOf(count, (ordered.keys.maxOrNull() ?: -1) + 1) else ordered.size
        return PackedArchive(GamevalFile(version, length, ordered).encode(), intArrayOf(0))
    }

    override fun archiveOf(path: String): Int? {
        val name = SourceFiles.guarded(path) ?: path
        if (name.contains('/') || !name.endsWith(GamevalCatalogs.EXTENSION)) {
            return null
        }
        return GamevalIndex.archiveId(name.dropLast(GamevalCatalogs.EXTENSION.length))
            ?: typeArchive(name.dropLast(GamevalCatalogs.EXTENSION.length))
    }

    override fun files(directory: Path, archive: Int): List<Path> {
        val types = ArrayList<String>(2)
        val domain = GamevalIndex.VAR_DOMAIN_BY_ARCHIVE[archive]
        if (domain == null) {
            types.add(GamevalIndex.typeName(archive))
        } else {
            types.add(domain.varType)
            types.add(domain.varbitType)
        }
        val files = ArrayList<Path>(4)
        for (type in types) {
            val file = directory.resolve("$type${GamevalCatalogs.EXTENSION}")
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

    /** The archive a `type_<n>` placeholder names. */
    private fun typeArchive(type: String): Int? {
        if (!type.startsWith(UNNAMED_PREFIX)) {
            return null
        }
        return type.substring(UNNAMED_PREFIX.length).toIntOrNull()
    }

    private const val UNNAMED_PREFIX = "type_"

    private class Split(val vars: LinkedHashMap<Int, ByteArray>, val varbits: LinkedHashMap<Int, ByteArray>, val offset: Int?)

    /** A combined var archive's vars and, rebased to their own ids, its varbits. */
    private fun split(file: GamevalFile): Split {
        val prefix = GamevalIndex.VARBIT_PREFIX[0].code.toByte()
        var offset: Int? = null
        for ((id, name) in file.names) {
            if (name.isNotEmpty() && name[0] == prefix && (offset == null || id < offset)) {
                offset = id
            }
        }
        val vars = LinkedHashMap<Int, ByteArray>()
        val varbits = LinkedHashMap<Int, ByteArray>()
        for ((id, name) in file.names) {
            if (name.isNotEmpty() && name[0] == prefix) {
                varbits[id - offset!!] = name.copyOfRange(1, name.size)
            } else {
                vars[id] = name
            }
        }
        return Split(vars, varbits, offset)
    }

    /** The catalog's exact text. */
    private fun catalog(revision: Int, archive: Int, file: GamevalFile, type: String, names: Map<Int, ByteArray>, offset: Int?): ByteArray {
        val out = StringBuilder(names.size * 40 + 128)
        out.append("{\n")
        out.append("  \"revision\": ").append(revision).append(",\n")
        out.append("  \"source\": ").append(SourceJson.quote("cache index ${GamevalIndex.INDEX} archive $archive")).append(",\n")
        out.append("  \"format\": ").append(file.version).append(",\n")
        if (file.dense) {
            val count = if (offset == null) file.count else file.count - offset
            out.append("  \"count\": ").append(count).append(",\n")
        }
        if (offset != null) {
            out.append("  \"offset\": ").append(offset).append(",\n")
        }
        out.append("  \"").append(GamevalCatalogs.ENTRIES).append("\": {")
        var first = true
        for ((id, name) in names) {
            if (!first) {
                out.append(',')
            }
            first = false
            out.append("\n    ").append(SourceJson.quote(key(id, type))).append(": ")
            out.append(SourceJson.quote(display(name, type)))
        }
        if (!first) {
            out.append("\n  ")
        }
        out.append("}\n}\n")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun key(id: Int, type: String): String =
        if (type == GamevalIndex.COMPONENT) "${(id ushr 16) and 0xffff}:${id and 0xffff}" else id.toString()

    private fun display(raw: ByteArray, type: String): String {
        val text = String(raw, Charsets.ISO_8859_1).lowercase()
        return if (type == GamevalIndex.COMPONENT) text.replaceFirst("__", ":") else text
    }

    private fun raw(display: String, archive: Int): ByteArray {
        val text = if (GamevalIndex.typeName(archive) == GamevalIndex.COMPONENT) display.replaceFirst(":", "__") else display
        return text.uppercase().toByteArray(Charsets.ISO_8859_1)
    }

    private class Catalog(val format: Int, val count: Int, val offset: Int?, val entries: Map<String, String>)

    private fun parse(bytes: ByteArray): Catalog {
        val root = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        val entries = LinkedHashMap<String, String>()
        root[GamevalCatalogs.ENTRIES]?.jsonObject?.forEach { (key, value) -> entries[keyId(key)] = value.jsonPrimitive.content }
        return Catalog(
            root["format"]?.jsonPrimitive?.int ?: GamevalFile.VERSION_DENSE,
            root["count"]?.jsonPrimitive?.int ?: 0,
            root["offset"]?.jsonPrimitive?.int,
            entries
        )
    }

    /** A component key back to its packed id; anything else is the id already. */
    private fun keyId(key: String): String {
        val separator = key.indexOf(':')
        if (separator < 0) {
            return key
        }
        val interfaceId = key.substring(0, separator).toInt()
        val componentId = key.substring(separator + 1).toInt()
        return ((interfaceId shl 16) or componentId).toString()
    }

    private val json = Json { ignoreUnknownKeys = true }
}
