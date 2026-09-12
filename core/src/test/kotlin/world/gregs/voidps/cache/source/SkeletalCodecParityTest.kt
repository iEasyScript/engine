package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.SkeletalAnimCodec
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.gameval.GamevalCatalogs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.CacheStore
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.ReferenceTable
import world.gregs.voidps.cache.store.SqliteStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every archive of the three skeletal animation indices, unpacked to real files and packed back, against
 * the group the cache holds - the chunk table of index 48's multi-frame groups included.
 *
 * Skipped where this machine has no cache. Index 48 is unpacked after index 1 and reads its framebases
 * out of the tree exactly as an unpack of the whole cache does, so the two indices' files are kept until
 * the last archive that needs them has been packed.
 */
class SkeletalCodecParityTest {

    private class Counts {
        val archives = AtomicInteger()
        val exact = AtomicInteger()
        val pristine = AtomicInteger()
        val verbatim = AtomicInteger()
        val mismatched = AtomicInteger()
    }

    @Test
    fun `skeletal archives pack back byte for byte`() {
        val cache = CacheFixture.resolveCacheDir()
        assumeTrue(cache != null, "no game cache on this machine — skipping")
        val tree = Files.createTempDirectory("skeletal-parity")
        SourceCodecs.context = SourceContext(tree, CacheEra.NXT_REVISION, GamevalCatalogs(null))
        val names = IndexNames.of(CacheEra.NXT_REVISION)
        val counts = LinkedHashMap<Int, Counts>()
        try {
            SqliteStore.open(cache!!, writable = false).use { store ->
                for (index in INDICES) {
                    counts[index] = index(store, tree.resolve(names.name(index)), index, keep = index == Index.ANIMATION_SKELETONS)
                }
            }
        } finally {
            clean(tree)
        }
        for ((index, count) in counts) {
            println(
                "index $index (${names.name(index)}): ${count.archives.get()} archives, ${count.exact.get()} exact, " +
                    "${count.pristine.get()} pristine, ${count.verbatim.get()} verbatim, ${count.mismatched.get()} mismatched"
            )
        }
        for ((index, count) in counts) {
            assertEquals(0, count.mismatched.get(), "index $index archives that did not pack back byte for byte")
        }
        assertEquals(counts.getValue(Index.ANIMATION_SKELETONS).archives.get(), counts.getValue(Index.ANIMATION_SKELETONS).exact.get())
        assertEquals(counts.getValue(Index.ANIMS_KEYFRAMES).archives.get(), counts.getValue(Index.ANIMS_KEYFRAMES).exact.get())
        val frames = counts.getValue(Index.ANIMS_RT7)
        assertEquals(frames.archives.get() - STUB_FRAMEBASES, frames.exact.get(), "animations decoded by the format")
        assertEquals(STUB_FRAMEBASES, frames.verbatim.get(), "animations whose framebase is a stub")
    }

    /**
     * The framebases outlive the archives that read them, so the tree is only emptied at the end - and
     * always, because the two indices that keep their files are gigabytes of JSON.
     */
    private fun clean(tree: Path) {
        SkeletalAnimCodec.reset()
        Files.walk(tree).use { stream ->
            for (path in stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path)
            }
        }
    }

    private fun index(store: CacheStore, directory: Path, index: Int, keep: Boolean): Counts {
        Files.createDirectories(directory)
        val codec = SourceCodecs.codec(index)
        val table = ReferenceTable.decode(Container.decode(store.readTable(index)!!, trailer = false).data())
        val counts = Counts()
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            val futures = table.archives.map { entry ->
                pool.submit { archive(store, directory, index, entry, codec, counts, keep) }
            }
            for (future in futures) {
                future.get()
            }
        } finally {
            pool.shutdown()
        }
        return counts
    }

    private fun archive(
        store: CacheStore,
        directory: Path,
        index: Int,
        entry: ArchiveEntry,
        codec: SourceCodec,
        counts: Counts,
        keep: Boolean
    ) {
        val bytes = store.read(index, entry.id) ?: return
        counts.archives.incrementAndGet()
        val group = Container.decode(bytes, null, store.trailers).data()
        val metadata = ArchiveMetadata(version = entry.version, nameHash = entry.nameHash)
        if (codec.fileIdsFromMetadata(entry.id)) {
            metadata.fileIds(entry.fileIds)
        }
        val split = ArchiveGroup.split(group, entry.fileIds)
        metadata.layout = split.layout
        metadata.layoutVersion = split.version
        val source = SourceArchive(index, entry.id, entry.nameHash, entry.fileIds, group, split.files, split.chunks, metadata)
        val unpacked = codec.unpack(directory, source)
        metadata.pristine = unpacked.pristine
        for (file in unpacked.files) {
            SourceFiles.write(directory.resolve(file.path), file.bytes)
        }
        when {
            metadata.verbatim -> counts.verbatim.incrementAndGet()
            unpacked.pristine.isNotEmpty() -> counts.pristine.incrementAndGet()
            else -> counts.exact.incrementAndGet()
        }
        val packed = codec.pack(directory, entry.id, metadata)
        if (!packed.group.contentEquals(group) || !packed.fileIds.contentEquals(entry.fileIds)) {
            counts.mismatched.incrementAndGet()
        }
        if (keep) {
            return
        }
        for (path in codec.files(directory, entry.id)) {
            SourceFiles.delete(path, directory)
        }
    }

    private companion object {
        /** Ascending, because index 48 needs index 1 on disk before it can read a frame. */
        private val INDICES = intArrayOf(Index.ANIMATION_SKELETONS, Index.ANIMS_RT7, Index.ANIMS_KEYFRAMES)

        /**
         * Animations naming a framebase with fewer transforms than their frames move, whose byte length
         * the format does not determine - see `re-resources/docs/cache/skeletal-animation-formats.md`,
         * which counts 53 because one of the 54 happens to end exactly whichever type is substituted for
         * the ones it is missing. That is an ambiguity rather than a decode, so all 54 keep their bytes.
         */
        private const val STUB_FRAMEBASES = 54
    }
}
