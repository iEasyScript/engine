package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.CacheStore
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.Converter
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import world.gregs.voidps.cache.store.SqliteStore
import world.gregs.voidps.cache.store.StoreKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** What one [Packer.pack] produced. */
class PackResult(
    val indices: Int,
    val archives: Int,
    /** Total size of the containers written, trailers included. */
    val bytes: Long,
    /** Indices copied verbatim from the source cache rather than packed from the tree. */
    val passthrough: Int,
    val millis: Long
) {
    override fun toString(): String =
        "$indices indices, $archives archives, $bytes bytes, $passthrough passthrough, in ${millis}ms"
}

/**
 * Builds a packed cache from a source tree, from nothing.
 *
 * Every archive is repacked, recompressed, re-encrypted and written into a fresh store, and every
 * reference table is rebuilt from the metadata and the archives that were just written, so nothing
 * in the output can disagree with anything else in it. [Builder] is the incremental version and
 * does the same thing to the archives that changed; this is what it falls back to.
 *
 * The version table is not derived here for a sector store: a legacy cache derives it whenever it is
 * opened, signed with whichever RSA key the server is configured with. A SQLite store keeps the
 * signed table in `js5-255`, and [VersionTables] writes it when the packer is given a key.
 */
object Packer {

    /**
     * Pack [tree] into [output], replacing whatever cache is already there.
     *
     * @param passthrough the cache the tree's passthrough indices are copied from; required when the
     *   tree has any
     * @param kind the store to build, the tree's own by default
     */
    fun pack(
        tree: SourceTree,
        output: Path,
        passthrough: CacheStore? = null,
        kind: StoreKind = tree.store,
        report: (String) -> Unit = {}
    ): PackResult {
        val start = System.nanoTime()
        if (tree.passthrough.isNotEmpty() && passthrough == null) {
            throw IOException(
                "The tree passes ${tree.passthrough.sorted()} through from its source cache, which was not given."
            )
        }
        SourceCodecs.context = SourceContext.of(tree)
        val threads = Runtime.getRuntime().availableProcessors()
        val pool = Executors.newFixedThreadPool(threads) { runnable ->
            Thread(runnable, "cache-pack").apply { isDaemon = true }
        }
        val manifest = BuildManifest(tree.directory.toAbsolutePath().toString())
        var archives = 0
        var bytes = 0L
        var indices = 0
        var copied = 0
        try {
            clear(output, kind)
            CacheStore.create(output, kind, tree.indexCount).use { store ->
                if (store is FileStore) {
                    // Nothing in a from-scratch build survives a crash anyway, and half a million
                    // fsync calls would cost more than the compression does.
                    store.durable = false
                }
                for (index in tree.indices()) {
                    val written = packIndex(tree, index, store, pool, manifest, threads, report)
                    archives += written.first
                    bytes += written.second
                    indices++
                }
                for (index in tree.passthrough.sorted()) {
                    val count = copy(passthrough!!, index, store, tree, report)
                    archives += count
                    copied++
                }
                store.flush()
                VersionTables.write(store, tree)
            }
            manifest.files.putAll(BuildManifest.scan(tree, emptyMap(), pool).files)
            manifest.write(output.toFile())
        } finally {
            pool.shutdown()
        }
        return PackResult(indices, archives, bytes, copied, (System.nanoTime() - start) / 1_000_000)
    }

    /** Pack one index into [store], returning its archive count and byte total. */
    private fun packIndex(
        tree: SourceTree,
        index: Int,
        store: CacheStore,
        pool: ExecutorService,
        manifest: BuildManifest,
        threads: Int,
        report: (String) -> Unit
    ): Pair<Int, Long> {
        val metadata = tree.metadata(index)
        val directory = tree.indexDirectory(index)
        val codec = SourceCodecs.codec(index)
        val ids = metadata.archiveIds()
        val entries = ArrayList<ArchiveEntry>(ids.size)
        val states = manifest.archives(index)
        var bytes = 0L
        if (ids.isNotEmpty()) {
            // Before the loop, once: a codec with shared state to build - the clientscript corpus
            // analysis - must not build it per archive, and a pack of every archive is the one case
            // where every one of them is going to need it.
            codec.packIndex(directory, index, ids, emptyList())
        }

        // Compression runs ahead of the store, which can only be written one archive at a time,
        // but only so far ahead: a whole index's containers at once is hundreds of megabytes.
        val window = threads * PIPELINE
        val pending = ArrayDeque<Pair<Int, Future<PackedContainer>>>()
        var next = 0
        while (next < ids.size || pending.isNotEmpty()) {
            while (next < ids.size && pending.size < window) {
                val archive = ids[next++]
                val archiveMetadata = metadata.archives.getValue(archive)
                pending.addLast(
                    archive to pool.submit<PackedContainer> {
                        SourcePack.container(directory, archive, archiveMetadata, codec, metadata, store.trailers)
                    }
                )
            }
            val (archive, future) = pending.removeFirst()
            val packed = future.get()
            store.write(index, archive, packed.bytes, packed.entry.version, packed.entry.crc)
            entries.add(packed.entry)
            states[archive] = ArchiveState(packed.entry.crc, SourceFiles.digest(metadata.archives.getValue(archive).write()))
            bytes += packed.bytes.size
        }

        val table = SourcePack.tableContainer(directory, metadata, SourcePack.table(metadata, entries))
        store.writeTable(index, table, 0, CRC.calculate(table, 0, table.size))
        bytes += table.size
        report("${tree.names.name(index)}: ${ids.size} archives, $bytes bytes")
        return ids.size to bytes
    }

    /** Copy one index verbatim from [source] into [store], adjusting the trailer when the two differ. */
    private fun copy(source: CacheStore, index: Int, store: CacheStore, tree: SourceTree, report: (String) -> Unit): Int {
        val table = source.readTable(index)
            ?: throw IOException("Passthrough index $index is not in the source cache ${source.directory}.")
        val entries = ReferenceTable.decode(Container.decode(table, trailer = false).data())
        var count = 0
        for (entry in entries.archives) {
            val bytes = source.read(index, entry.id) ?: continue
            val container = Converter.retrail(bytes, source.trailers, store.trailers, entry.version)
            store.write(index, entry.id, container, entry.version, entry.crc)
            count++
        }
        store.writeTable(index, table, 0, CRC.calculate(table, 0, table.size))
        report("${tree.names.name(index)}: $count archives copied through")
        return count
    }

    /** A pack writes a whole cache; anything already in [output] is last build's, not this one's. */
    private fun clear(output: Path, kind: StoreKind) {
        Files.createDirectories(output)
        Files.list(output).use { stream ->
            for (file in stream) {
                if (!Files.isRegularFile(file)) {
                    continue
                }
                val name = file.fileName.toString()
                val ours = when (kind) {
                    StoreKind.SECTOR -> name.startsWith("${FileStore.CACHE_FILE_NAME}.")
                    StoreKind.SQLITE -> SqliteStore.indexOf(name) != null || name.endsWith("$EXTENSION-wal") || name.endsWith("$EXTENSION-shm")
                }
                if (ours || name == BuildManifest.FILE) {
                    Files.delete(file)
                }
            }
        }
    }

    private const val EXTENSION = SqliteStore.EXTENSION

    /** How many archives per thread may be compressed ahead of the store writer. */
    private const val PIPELINE = 8
}
