package world.gregs.voidps.cache.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.gregs.voidps.cache.store.StoreKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * A cache source tree: the whole packed cache unpacked into one file per asset, plus the little
 * metadata the files cannot carry. See `docs/cache-source.md` for the layout and the contract.
 *
 * ```
 * <tree>/cache.json                 format version, revision, store kind, index count, passthrough
 * <tree>/gamevals/<type>.json       the name catalogs, which are cache assets too
 * <tree>/<index name>/index.json    the index's reference table, minus everything derivable
 * <tree>/<index name>/...           the archives, laid out by the index's codec
 * ```
 *
 * This class is the directory and nothing more: it knows where things go, reads and writes
 * `cache.json`, and leaves the contents to [Unpacker], [Packer] and [Builder].
 */
class SourceTree private constructor(
    val directory: Path,
    /** The layout version this tree was written with. */
    val format: Int,
    /** The client build the cache belongs to - 727 for the legacy cache, the NXT build otherwise. */
    val revision: Int,
    /** The store the packed cache is read from and built into. */
    val store: StoreKind,
    /** How many indices the tree describes, which is how many the packed cache will have. */
    val indexCount: Int,
    /**
     * Indices the tree does not hold at all and a pack copies verbatim from the cache it was
     * unpacked from - the audio indices, whose size nobody wants in a git repository.
     */
    val passthrough: Set<Int>
) {

    val era: CacheEra
        get() = CacheEra.of(revision)

    /** The index directory names for this tree's era. */
    val names: IndexNames = IndexNames.of(revision)

    /** `<tree>/cache.json`. */
    val cacheFile: Path
        get() = directory.resolve(CACHE_FILE)

    /** `<tree>/gamevals`, the catalogs: index 67 unpacked when the cache has it, seeded from outside otherwise. */
    val gamevals: Path
        get() = directory.resolve(names.name(IndexNames.GAMEVALS))

    /** [index]'s directory, whether or not it exists. */
    fun indexDirectory(index: Int): Path = directory.resolve(names.name(index))

    /** [index]'s `index.json`. */
    fun indexFile(index: Int): Path = indexDirectory(index).resolve(INDEX_FILE)

    /** Every index the tree actually holds - those with an `index.json` - ascending. */
    fun indices(): List<Int> {
        val found = ArrayList<Int>(indexCount)
        for (index in 0 until indexCount) {
            if (Files.isRegularFile(indexFile(index))) {
                found.add(index)
            }
        }
        return found
    }

    /** Read [index]'s metadata. */
    fun metadata(index: Int): IndexMetadata =
        IndexMetadata.read(Files.readString(indexFile(index)), index)

    /** Write [index]'s metadata, leaving the file alone when it already says exactly this. */
    fun writeMetadata(metadata: IndexMetadata): Boolean =
        SourceFiles.write(indexFile(metadata.index), metadata.write().toByteArray(Charsets.UTF_8))

    /**
     * A read-only inventory of the tree: per index the codec, the archive, file, pristine and
     * opaque counts, and every pristine sidecar whose guard no longer holds. See [SourceStatus].
     */
    fun status(): SourceStatus = SourceStatus.of(this)

    /** The tree relative, `/` separated path of [path], or null when it is outside the tree. */
    fun relative(path: Path): String? {
        if (!path.startsWith(directory)) {
            return null
        }
        return directory.relativize(path).joinToString("/") { it.name }
    }

    /** A copy of this tree's description with a different [passthrough] set or [indexCount]. */
    fun with(passthrough: Set<Int> = this.passthrough, indexCount: Int = this.indexCount): SourceTree =
        SourceTree(directory, format, revision, store, indexCount, passthrough)

    /** `cache.json`'s exact text. */
    fun describe(): String = buildString {
        append("{\n")
        append("  \"format\": ").append(format).append(",\n")
        append("  \"revision\": ").append(revision).append(",\n")
        append("  \"store\": ").append(SourceJson.quote(store.id)).append(",\n")
        append("  \"indices\": ").append(indexCount)
        if (passthrough.isNotEmpty()) {
            append(",\n  \"passthrough\": ").append(SourceJson.array(passthrough.sorted().toIntArray()))
        }
        append("\n}\n")
    }

    override fun toString(): String = "SourceTree(${directory.toAbsolutePath()}, revision $revision, ${store.id})"

    companion object {
        /** The layout version. Bumped when the tree's shape changes in a way readers must notice. */
        const val FORMAT = 2

        const val CACHE_FILE = "cache.json"
        const val INDEX_FILE = "index.json"

        /** An archive nothing can open, kept as its container bytes minus the version trailer. */
        const val OPAQUE_EXTENSION = ".container"

        /** The compressed payload of an archive no encoder here reproduces, kept beside its files. */
        const val PAYLOAD_EXTENSION = ".payload"

        /** A multi-chunk group kept whole because its codec cannot rebuild the split. */
        const val VERBATIM_EXTENSION = ".grp"

        private val json = Json { ignoreUnknownKeys = true }

        /** Open the tree at [directory], which must already have a `cache.json`. */
        fun open(directory: File): SourceTree = open(directory.toPath())

        fun open(directory: Path): SourceTree {
            val file = directory.resolve(CACHE_FILE)
            if (!Files.isRegularFile(file)) {
                throw IOException("No $CACHE_FILE in ${directory.toAbsolutePath()}; this is not a cache source tree.")
            }
            val root = json.parseToJsonElement(Files.readString(file)).jsonObject
            val format = root["format"]?.jsonPrimitive?.int ?: FORMAT
            require(format == FORMAT) {
                "The tree at ${directory.toAbsolutePath()} is format $format; this build reads format $FORMAT."
            }
            val revision = root["revision"]?.jsonPrimitive?.int
                ?: throw IOException("$CACHE_FILE in ${directory.toAbsolutePath()} names no revision.")
            val store = root["store"]?.jsonPrimitive?.content?.let { StoreKind.of(it) }
                ?: if (CacheEra.of(revision) == CacheEra.LEGACY) StoreKind.SECTOR else StoreKind.SQLITE
            val passthrough = root["passthrough"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet() ?: emptySet()
            return SourceTree(
                directory = directory,
                format = format,
                revision = revision,
                store = store,
                indexCount = root["indices"]?.jsonPrimitive?.int ?: 0,
                passthrough = passthrough
            )
        }

        /** Create (or re-describe) the tree at [directory] and write its `cache.json`. */
        fun create(
            directory: Path,
            revision: Int,
            store: StoreKind,
            indexCount: Int,
            passthrough: Set<Int> = emptySet()
        ): SourceTree {
            Files.createDirectories(directory)
            val tree = SourceTree(directory, FORMAT, revision, store, indexCount, passthrough)
            SourceFiles.write(tree.cacheFile, tree.describe().toByteArray(Charsets.UTF_8))
            return tree
        }
    }
}
