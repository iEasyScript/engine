package world.gregs.voidps.cache.store

import java.io.Closeable
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path

/** The two on-disk shapes a packed cache comes in. */
enum class StoreKind(val id: String) {
    /** `main_file_cache.dat2` + `.idx<n>`: every client before NXT. */
    SECTOR("sector"),

    /** `js5-<n>.jcache` SQLite databases, one per index: the NXT client. */
    SQLITE("sqlite");

    companion object {
        fun of(id: String): StoreKind = entries.firstOrNull { it.id == id.lowercase() }
            ?: throw IllegalArgumentException("Unknown cache store kind '$id'.")
    }
}

/**
 * A packed cache as a store of containers, whatever the files on disk look like.
 *
 * Everything above this - the unpacker, the packer, the verifier, the converter - deals in
 * `(index, archive) -> container bytes` and `index -> reference table bytes` and nothing else, so
 * the same tree unpacks from a sector store and packs into a SQLite one. The one shape difference
 * that reaches the container bytes is the version [trailers] a sector store keeps on every archive
 * container: a store says whether its containers carry one, and a caller that moves containers
 * between stores adds or strips it.
 */
interface CacheStore : Closeable {

    val directory: Path

    val kind: StoreKind

    /** Whether the archive containers this store holds end in the two byte version trailer. */
    val trailers: Boolean

    val writable: Boolean

    /** One more than the highest index that could exist, which is index 255's archive count. */
    fun indexCount(): Int

    /** Every index that has a reference table, ascending. */
    fun indices(): IntArray

    /** [index]'s reference table container, or null when the index does not exist. */
    fun readTable(index: Int): ByteArray?

    /** The container bytes of [archive] as stored, or null when the archive does not exist. */
    fun read(index: Int, archive: Int): ByteArray?

    /** Every archive id [index] holds a container for, ascending. */
    fun archives(index: Int): IntArray

    /**
     * Replace or create [archive]'s container.
     *
     * @param version the archive's full version, which a SQLite store keeps beside the bytes and a
     *   sector store expects to already be in the container's trailer
     * @param crc the container's CRC without its trailer, as the reference table records it
     */
    fun write(index: Int, archive: Int, container: ByteArray, version: Int = 0, crc: Int = 0)

    /** Replace or create [index]'s reference table container. */
    fun writeTable(index: Int, container: ByteArray, version: Int = 0, crc: Int = 0)

    /** Forget [archive]; a no-op when it does not exist. */
    fun remove(index: Int, archive: Int)

    /** Forget [index]'s reference table and everything that hangs off it. */
    fun removeIndex(index: Int)

    /** Make everything written so far durable. */
    fun flush() {}

    companion object {

        /** Whether [directory] holds a store of either kind. */
        fun kindOf(directory: Path): StoreKind? = when {
            Files.isRegularFile(directory.resolve(FileStore.DATA_FILE)) -> StoreKind.SECTOR
            hasSqlite(directory) -> StoreKind.SQLITE
            else -> null
        }

        /** Open the store in [directory], whichever kind it is. */
        fun open(directory: Path, writable: Boolean = false): CacheStore = when (kindOf(directory)) {
            StoreKind.SECTOR -> FileStore.open(directory.toFile(), writable)
            StoreKind.SQLITE -> SqliteStore.open(directory, writable)
            null -> throw FileNotFoundException("No packed cache in ${directory.toAbsolutePath()}.")
        }

        /** Create an empty writable store of [kind] in [directory]. */
        fun create(directory: Path, kind: StoreKind, indexCount: Int): CacheStore = when (kind) {
            StoreKind.SECTOR -> FileStore.create(directory.toFile(), indexCount)
            StoreKind.SQLITE -> SqliteStore.create(directory, indexCount)
        }

        private fun hasSqlite(directory: Path): Boolean {
            if (!Files.isDirectory(directory)) {
                return false
            }
            Files.list(directory).use { stream ->
                return stream.anyMatch { SqliteStore.indexOf(it.fileName.toString()) != null }
            }
        }
    }
}
