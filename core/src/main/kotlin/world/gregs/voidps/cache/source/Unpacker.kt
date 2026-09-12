package world.gregs.voidps.cache.source

import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.CacheStore
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.ReferenceTable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.name

/** What one [Unpacker.unpack] did. */
class UnpackReport(
    val indices: Int,
    val archives: Int,
    val files: Int,
    /** Files whose contents differed from what was on disk, so had to be written. */
    val written: Int,
    /** Files left over from archives that no longer exist. */
    val deleted: Int,
    /** Archives kept as container bytes because nothing could open them, or because they were asked to be. */
    val opaque: Int,
    /** Archives whose codec did not reproduce the group and fell back to pristine bytes. */
    val inexact: Int,
    /** Editable files whose codec could not reproduce the shipped bytes, so a sidecar carries them. */
    val pristine: Int,
    /** Archives whose compressed payload no encoder reproduces, so a sidecar carries it. */
    val payloads: Int,
    /** Multi-chunk groups kept whole because their codec cannot rebuild the split. */
    val verbatim: Int,
    /** Archives listed in a reference table with no container behind them. */
    val missing: Int,
    /** Indices left out of the tree entirely, to be copied from this cache at pack time. */
    val passthrough: Int,
    val bytes: Long,
    val millis: Long
) {
    override fun toString(): String =
        "$archives archives, $files files ($bytes bytes), $written written, $deleted deleted, " +
            "$opaque opaque, $pristine pristine, $inexact inexact, $payloads payloads, $verbatim verbatim, $missing missing, " +
            "$passthrough passthrough indices, in ${millis}ms"
}

/**
 * How an unpack is to treat the cache.
 *
 * @param indices the only indices to unpack, or null for every one the cache has
 * @param passthrough indices to leave out of the tree and copy verbatim from the cache at pack time
 * @param opaque indices whose archives are kept as their container bytes, unopened
 * @param keys the keys that open encrypted archives
 * @param gamevals a directory of catalogs to seed `gamevals/` from when the cache has no gameval index
 * @param threads how many archives to unpack at once
 */
class UnpackOptions(
    val indices: Set<Int>? = null,
    val passthrough: Set<Int> = emptySet(),
    val opaque: Set<Int> = emptySet(),
    val keys: XteaKeys = XteaKeys.NONE,
    val gamevals: Path? = null,
    val threads: Int = Runtime.getRuntime().availableProcessors()
)

/**
 * Turns a packed cache into a source tree.
 *
 * Every index with a reference table gets a directory, an `index.json` holding what its files
 * cannot say, its archives laid out by the index's codec, and whatever that codec writes for the
 * index as a whole ([SourceCodec.unpackIndex]). Nothing derivable is written down: CRCs,
 * whirlpools, lengths and the file ids a codec spells out in its own layout are all recomputed when
 * the tree is packed, so they cannot rot.
 *
 * Unpacking the same cache twice writes nothing the second time. Files are compared before they
 * are written and files belonging to archives that no longer exist are deleted, so the tree is a
 * function of the cache and git sees a diff only where the cache actually changed.
 */
object Unpacker {

    fun unpack(
        store: CacheStore,
        tree: SourceTree,
        options: UnpackOptions = UnpackOptions(),
        report: (String) -> Unit = {}
    ): UnpackReport {
        val start = System.nanoTime()
        SourceFiles.write(tree.cacheFile, tree.describe().toByteArray(Charsets.UTF_8))
        SourceCodecs.context = SourceContext.of(tree)
        val counts = Counts()
        val available = store.indices()
        if (!available.contains(IndexNames.GAMEVALS)) {
            val seeded = seedGamevals(tree, options.gamevals)
            if (seeded > 0) {
                report("${tree.names.name(IndexNames.GAMEVALS)}: $seeded catalogs seeded from ${options.gamevals}")
            }
        }
        val pool = Executors.newFixedThreadPool(maxOf(options.threads, 1)) { runnable ->
            Thread(runnable, "cache-unpack").apply { isDaemon = true }
        }
        try {
            for (index in available) {
                if (options.indices != null && index !in options.indices) {
                    continue
                }
                if (index in tree.passthrough) {
                    counts.passthrough.incrementAndGet()
                    report("${tree.names.name(index)}: passthrough")
                    continue
                }
                val tableBytes = store.readTable(index) ?: continue
                unpackIndex(store, tree, index, tableBytes, options, pool, counts, report)
                counts.indices.incrementAndGet()
            }
        } finally {
            pool.shutdown()
        }
        return counts.report(System.nanoTime() - start)
    }

    private fun unpackIndex(
        store: CacheStore,
        tree: SourceTree,
        index: Int,
        tableBytes: ByteArray,
        options: UnpackOptions,
        pool: ExecutorService,
        counts: Counts,
        report: (String) -> Unit
    ) {
        val directory = tree.indexDirectory(index)
        Files.createDirectories(directory)
        val tableContainer = Container.decode(tableBytes, trailer = false)
        val encoded = tableContainer.data()
        val table = ReferenceTable.decode(encoded)
        val metadata = IndexMetadata(
            index = index,
            format = table.format,
            revision = table.revision,
            named = table.named,
            whirlpool = table.whirlpool,
            lengths = table.lengths,
            checksums = table.checksums,
            compression = tableContainer.compression
        )
        val expected = ConcurrentHashMap.newKeySet<String>()
        val settings = tableContainer.settings(encoded)
        if (settings != null) {
            metadata.settings = settings
        } else {
            metadata.payload = SourceFiles.sha256(encoded)
            expected.add(SourcePack.TABLE_PAYLOAD)
            counts.payloads.incrementAndGet()
            if (SourceFiles.write(directory.resolve(SourcePack.TABLE_PAYLOAD), tableContainer.payload)) {
                counts.written.incrementAndGet()
            }
        }

        val codec = SourceCodecs.codec(index)
        val opaque = index in options.opaque
        val archives = ConcurrentHashMap<Int, ArchiveMetadata>(table.archiveCount)
        val futures = table.archives.map { entry ->
            pool.submit {
                val archive = unpackArchive(store, directory, index, entry, options.keys, codec, opaque, counts, expected)
                if (archive != null) {
                    archives[entry.id] = archive
                }
            }
        }
        for (future in futures) {
            future.get()
        }
        metadata.archives.putAll(archives)

        // The index's own files, if it has any: a corpus-wide answer no single archive can give.
        // They are claimed here exactly as an archive's files are, which is what keeps [prune] from
        // deleting them again a moment later.
        for (file in codec.unpackIndex(directory, index, archives.keys.sorted().toIntArray())) {
            expected.add(file.path)
            counts.files.incrementAndGet()
            counts.bytes.addAndGet(file.bytes.size.toLong())
            if (SourceFiles.write(directory.resolve(file.path), file.bytes)) {
                counts.written.incrementAndGet()
            }
        }

        expected.add(SourceTree.INDEX_FILE)
        if (tree.writeMetadata(metadata)) {
            counts.written.incrementAndGet()
        }
        counts.files.incrementAndGet()
        val deleted = prune(directory, expected)
        counts.deleted.addAndGet(deleted)
        report(
            "${tree.names.name(index)}: ${archives.size} archives, ${expected.size - 1} files" +
                if (deleted > 0) ", $deleted deleted" else ""
        )
    }

    private fun unpackArchive(
        store: CacheStore,
        directory: Path,
        index: Int,
        entry: ArchiveEntry,
        keys: XteaKeys,
        codec: SourceCodec,
        forceOpaque: Boolean,
        counts: Counts,
        expected: MutableSet<String>
    ): ArchiveMetadata? {
        val bytes = store.read(index, entry.id)
        if (bytes == null) {
            counts.missing.incrementAndGet()
            return null
        }
        counts.archives.incrementAndGet()
        val metadata = ArchiveMetadata(version = entry.version, nameHash = entry.nameHash)
        val fileNames = entry.files.filter { it.nameHash != 0 }
        if (fileNames.isNotEmpty()) {
            metadata.fileNames = fileNames.associate { it.id to it.nameHash }
        }
        if (forceOpaque) {
            return opaque(directory, entry, bytes, metadata, store.trailers, counts, expected)
        }
        val xtea = keys.keys(index, entry.id, entry.nameHash)
        val container = try {
            Container.decode(bytes, xtea, store.trailers)
        } catch (e: Exception) {
            return opaque(directory, entry, bytes, metadata, store.trailers, counts, expected)
        }
        metadata.compression = container.compression
        if (xtea != null) {
            metadata.xtea = xtea
        }

        // A container that will not open at all is kept as it is; one whose payload no encoder
        // here reproduces keeps its payload beside the group. Either way parity holds.
        val group = try {
            container.data()
        } catch (e: Exception) {
            return opaque(directory, entry, bytes, metadata, store.trailers, counts, expected)
        }
        val settings = container.settings(group)
        if (settings != null) {
            metadata.settings = settings
        } else {
            metadata.payload = SourceFiles.sha256(group)
            val path = "${entry.id}${SourceTree.PAYLOAD_EXTENSION}"
            expected.add(path)
            counts.payloads.incrementAndGet()
            counts.files.incrementAndGet()
            counts.bytes.addAndGet(container.payload.size.toLong())
            if (SourceFiles.write(directory.resolve(path), container.payload)) {
                counts.written.incrementAndGet()
            }
        }

        if (codec.fileIdsFromMetadata(entry.id)) {
            metadata.fileIds(entry.fileIds)
        }
        val split = ArchiveGroup.split(group, entry.fileIds)
        metadata.layout = split.layout
        metadata.layoutVersion = split.version
        if (split.chunks.size > 1 && !codec.preservesChunks(entry.id)) {
            return verbatim(directory, entry, group, metadata, counts, expected)
        }
        val source = SourceArchive(index, entry.id, entry.nameHash, entry.fileIds, group, split.files, split.chunks, metadata)
        val unpacked = codec.unpack(directory, source)
        if (unpacked.pristine.isNotEmpty()) {
            metadata.pristine = unpacked.pristine
            counts.pristine.addAndGet(unpacked.pristine.size)
        }
        for (file in unpacked.files) {
            expected.add(file.path)
            counts.files.incrementAndGet()
            counts.bytes.addAndGet(file.bytes.size.toLong())
            if (SourceFiles.write(directory.resolve(file.path), file.bytes)) {
                counts.written.incrementAndGet()
            }
        }
        if (!codec.exact(entry.id) && !codec.pack(directory, entry.id, metadata).group.contentEquals(group)) {
            counts.inexact.incrementAndGet()
        }
        return metadata
    }

    /**
     * A group stored in several chunks, which the index's codec would rejoin as one: kept whole,
     * chunk table and all, so the split comes back exactly. The typed codec that derives the split
     * from the format is what replaces this, index by index.
     */
    private fun verbatim(
        directory: Path,
        entry: ArchiveEntry,
        group: ByteArray,
        metadata: ArchiveMetadata,
        counts: Counts,
        expected: MutableSet<String>
    ): ArchiveMetadata {
        metadata.verbatim = true
        metadata.fileIds(entry.fileIds)
        val path = "${entry.id}${SourceTree.VERBATIM_EXTENSION}"
        expected.add(path)
        counts.verbatim.incrementAndGet()
        counts.files.incrementAndGet()
        counts.bytes.addAndGet(group.size.toLong())
        if (SourceFiles.write(directory.resolve(path), group)) {
            counts.written.incrementAndGet()
        }
        return metadata
    }

    /**
     * An archive nothing can open - or one asked to be left closed - is kept as the container
     * itself, minus the version trailer the metadata already holds, and packed straight back out.
     */
    private fun opaque(
        directory: Path,
        entry: ArchiveEntry,
        bytes: ByteArray,
        metadata: ArchiveMetadata,
        trailers: Boolean,
        counts: Counts,
        expected: MutableSet<String>
    ): ArchiveMetadata {
        metadata.opaque = true
        metadata.xtea = null
        metadata.fileIds(entry.fileIds)
        metadata.compression = if (bytes.isNotEmpty()) runCatching { world.gregs.voidps.cache.store.Compression.of(bytes[0].toInt() and 0xff) }.getOrDefault(metadata.compression) else metadata.compression
        val trailer = if (trailers && bytes.size >= Container.TRAILER_SIZE && (entry.version and 0xffff) ==
            (((bytes[bytes.size - 2].toInt() and 0xff) shl 8) or (bytes[bytes.size - 1].toInt() and 0xff))
        ) Container.TRAILER_SIZE else 0
        val path = "${entry.id}${SourceTree.OPAQUE_EXTENSION}"
        val payload = bytes.copyOf(bytes.size - trailer)
        expected.add(path)
        counts.opaque.incrementAndGet()
        counts.files.incrementAndGet()
        counts.bytes.addAndGet(payload.size.toLong())
        if (SourceFiles.write(directory.resolve(path), payload)) {
            counts.written.incrementAndGet()
        }
        return metadata
    }

    /** Delete everything under [directory] that is not in [expected], and any directory it empties. */
    private fun prune(directory: Path, expected: Set<String>): Int {
        var deleted = 0
        val stale = ArrayList<Path>()
        Files.walk(directory).use { stream ->
            for (path in stream) {
                if (!Files.isRegularFile(path)) {
                    continue
                }
                val relative = directory.relativize(path).joinToString("/") { it.name }
                if (!expected.contains(relative)) {
                    stale.add(path)
                }
            }
        }
        for (path in stale) {
            SourceFiles.delete(path, directory)
            deleted++
        }
        return deleted
    }

    /**
     * Copy the gameval catalogs in from [source]; they are cache assets and belong in the tree.
     * Only used when the cache itself has no gameval index to unpack them from.
     */
    private fun seedGamevals(tree: SourceTree, source: Path?): Int {
        if (source == null || !Files.isDirectory(source)) {
            return 0
        }
        var copied = 0
        Files.list(source).use { stream ->
            for (file in stream) {
                if (!Files.isRegularFile(file) || !file.name.endsWith(".json")) {
                    continue
                }
                SourceFiles.write(tree.gamevals.resolve(file.name), Files.readAllBytes(file))
                copied++
            }
        }
        return copied
    }

    private class Counts {
        val indices = AtomicInteger()
        val archives = AtomicInteger()
        val files = AtomicInteger()
        val written = AtomicInteger()
        val deleted = AtomicInteger()
        val opaque = AtomicInteger()
        val inexact = AtomicInteger()
        val pristine = AtomicInteger()
        val payloads = AtomicInteger()
        val verbatim = AtomicInteger()
        val missing = AtomicInteger()
        val passthrough = AtomicInteger()
        val bytes = AtomicLong()

        fun report(nanos: Long) = UnpackReport(
            indices.get(), archives.get(), files.get(), written.get(), deleted.get(),
            opaque.get(), inexact.get(), pristine.get(), payloads.get(), verbatim.get(), missing.get(), passthrough.get(),
            bytes.get(), nanos / 1_000_000
        )
    }
}
