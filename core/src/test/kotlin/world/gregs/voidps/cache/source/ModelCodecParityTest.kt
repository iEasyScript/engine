package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.CacheStore
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.ReferenceTable
import world.gregs.voidps.cache.store.SqliteStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every archive of index 47, decoded, written as a `.glb`, read back off disk and re-encoded, against
 * the group the cache holds.
 *
 * Skipped where this machine has no cache. Each model's files are deleted as soon as it has been
 * proved, so the whole run costs one model of disk rather than the whole index, and the temporary
 * tree goes in a `finally` either way.
 */
class ModelCodecParityTest {

    private class Counts {
        val archives = AtomicInteger()
        val exact = AtomicInteger()
        val pristine = AtomicInteger()
        val mismatched = AtomicInteger()
        val failures = ConcurrentLinkedQueue<String>()
        val groupBytes = AtomicLong()
        val fileBytes = AtomicLong()
    }

    @Test
    fun `models pack back byte for byte`() {
        val cache = CacheFixture.resolveCacheDir()
        assumeTrue(cache != null, "no game cache on this machine — skipping")
        val tree = Files.createTempDirectory("model-parity")
        val counts = Counts()
        try {
            SqliteStore.open(cache!!, writable = false).use { store ->
                models(store, tree, counts)
            }
        } finally {
            clean(tree)
        }
        println(
            "index ${Index.MODELS_RT7}: ${counts.archives.get()} archives, ${counts.exact.get()} exact, " +
                "${counts.pristine.get()} pristine, ${counts.mismatched.get()} mismatched"
        )
        println(
            "groups ${gigabytes(counts.groupBytes.get())}, glb ${gigabytes(counts.fileBytes.get())} " +
                "(${percent(counts.fileBytes.get(), counts.groupBytes.get())} of the decompressed groups)"
        )
        for (failure in counts.failures.take(FAILURES_SHOWN)) {
            println(failure)
        }
        assertEquals(0, counts.failures.size, "archives the codec could not process")
        assertEquals(0, counts.mismatched.get(), "archives that did not pack back byte for byte")
        assertEquals(counts.archives.get() - OVERFLOWED_INDEX_COUNTS, counts.exact.get(), "models the encoder reproduces")
        assertEquals(OVERFLOWED_INDEX_COUNTS, counts.pristine.get(), "models whose index count overflowed its field")
    }

    private fun models(store: CacheStore, tree: Path, counts: Counts) {
        val directory = tree.resolve(IndexNames.of(CacheEra.NXT_REVISION).name(Index.MODELS_RT7))
        Files.createDirectories(directory)
        val codec = SourceCodecs.codec(Index.MODELS_RT7)
        val table = ReferenceTable.decode(Container.decode(store.readTable(Index.MODELS_RT7)!!, trailer = false).data())
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val futures = table.archives.map { entry ->
            pool.submit { archive(store, directory, entry, codec, counts) }
        }
        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        for (future in futures) {
            future.get()
        }
    }

    private fun archive(store: CacheStore, directory: Path, entry: ArchiveEntry, codec: SourceCodec, counts: Counts) {
        try {
            model(store, directory, entry, codec, counts)
        } catch (e: Exception) {
            counts.failures.add("model ${entry.id}: $e")
        }
    }

    private fun model(store: CacheStore, directory: Path, entry: ArchiveEntry, codec: SourceCodec, counts: Counts) {
        val bytes = store.read(Index.MODELS_RT7, entry.id) ?: return
        counts.archives.incrementAndGet()
        val group = Container.decode(bytes, trailer = false).data()
        counts.groupBytes.addAndGet(group.size.toLong())
        val metadata = ArchiveMetadata(version = entry.version, nameHash = entry.nameHash)
        if (codec.fileIdsFromMetadata(entry.id)) {
            metadata.fileIds(entry.fileIds)
        }
        val split = ArchiveGroup.split(group, entry.fileIds)
        val source = SourceArchive(
            Index.MODELS_RT7, entry.id, entry.nameHash, entry.fileIds, group, split.files, split.chunks, metadata
        )
        val unpacked = codec.unpack(directory, source)
        metadata.pristine = unpacked.pristine
        for (file in unpacked.files) {
            counts.fileBytes.addAndGet(file.bytes.size.toLong())
            SourceFiles.write(directory.resolve(file.path), file.bytes)
        }
        if (unpacked.pristine.isEmpty()) counts.exact.incrementAndGet() else counts.pristine.incrementAndGet()
        val packed = codec.pack(directory, entry.id, metadata)
        if (!packed.group.contentEquals(group) || !packed.fileIds.contentEquals(entry.fileIds)) {
            counts.mismatched.incrementAndGet()
        }
        for (path in codec.files(directory, entry.id)) {
            SourceFiles.delete(path, directory)
        }
    }

    /** Never throws: a tree that outlives a failure must not be what the report says went wrong. */
    private fun clean(tree: Path) {
        runCatching {
            Files.walk(tree).use { stream ->
                for (path in stream.sorted(Comparator.reverseOrder()).toList()) {
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }

    private fun gigabytes(bytes: Long) = "%.2f GB".format(bytes / 1e9)

    private fun percent(part: Long, whole: Long) = "%.0f%%".format(part * 100.0 / whole)

    private companion object {
        /**
         * The six assets whose true index count is above what the file's own 16 bit field holds, so the
         * high bits are stored nowhere and no writer puts them back - see
         * `re-resources/docs/cache/rt7-model-format.md`.
         */
        private const val OVERFLOWED_INDEX_COUNTS = 6

        private const val FAILURES_SHOWN = 10
    }
}
