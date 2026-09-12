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
import world.gregs.voidps.cache.type.decoder.ConfigGroup76Decoder
import world.gregs.voidps.cache.type.decoder.EffectAnimDecoder
import world.gregs.voidps.cache.type.decoder.FontMetricsDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The audio, world map area and font metrics indices unpacked to real files and packed back,
 * against the group the cache holds.
 *
 * Skipped where this machine has no cache. The audio indices are gigabytes, so an archive's files
 * are deleted the moment they have been packed back, the tree goes with the test, and only the
 * counts are kept; the stream index is walked in a deterministic sample rather than in full.
 */
class AudioWorldmapCodecParityTest {

    private class Counts {
        val archives = AtomicInteger()
        val files = AtomicInteger()
        val pristine = AtomicInteger()
        val mismatched = AtomicInteger()
    }

    @Test
    fun `audio, world map area and font metrics archives pack back byte for byte`() {
        val cache = CacheFixture.resolveCacheDir()
        assumeTrue(cache != null, "no game cache on this machine — skipping")
        val tree = Files.createTempDirectory("audio-worldmap-parity")
        SourceCodecs.context = SourceContext.none(CacheEra.NXT_REVISION)
        var mismatched = 0
        try {
            SqliteStore.open(cache!!, writable = false).use { store ->
                for (index in TYPED_INDICES) {
                    mismatched += index(store, tree, index) { true }
                }
                mismatched += index(store, tree, Index.CONFIGS) { it == EffectAnimDecoder.ARCHIVE || it == ConfigGroup76Decoder.ARCHIVE }
                mismatched += index(store, tree, Index.VORBIS) { true }
                mismatched += index(store, tree, Index.AUDIO_STREAMS) { it % STREAM_SAMPLE == 0 }
            }
        } finally {
            remove(tree)
        }
        assertEquals(0, mismatched, "archives that did not pack back byte for byte")
    }

    private fun index(store: CacheStore, tree: Path, index: Int, wanted: (Int) -> Boolean): Int {
        val table = ReferenceTable.decode(Container.decode(store.readTable(index)!!, trailer = false).data())
        val directory = Files.createDirectories(tree.resolve(index.toString()))
        val codec = SourceCodecs.codec(index)
        val counts = Counts()
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            val futures = table.archives.filter { wanted(it.id) }.map { entry ->
                pool.submit { archive(store, directory, index, entry, codec, counts) }
            }
            for (future in futures) {
                future.get()
            }
        } finally {
            pool.shutdown()
        }
        println(
            "index $index: ${counts.archives.get()} archives, ${counts.files.get()} files, " +
                "${counts.pristine.get()} pristine, ${counts.mismatched.get()} mismatched"
        )
        return counts.mismatched.get()
    }

    private fun archive(
        store: CacheStore,
        directory: Path,
        index: Int,
        entry: ArchiveEntry,
        codec: SourceCodec,
        counts: Counts
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
        counts.pristine.addAndGet(unpacked.pristine.size)
        for (file in unpacked.files) {
            counts.files.incrementAndGet()
            SourceFiles.write(directory.resolve(file.path), file.bytes)
        }
        if (!codec.pack(directory, entry.id, metadata).group.contentEquals(group)) {
            counts.mismatched.incrementAndGet()
        }
        for (file in unpacked.files) {
            SourceFiles.delete(directory.resolve(file.path), directory)
        }
    }

    /** The tree is gigabytes while it is being walked, so nothing of it outlives the test. */
    private fun remove(tree: Path) {
        Files.walk(tree).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private companion object {
        /** One group in this many of the stream index, whose whole is 7.6 GB. */
        const val STREAM_SAMPLE = 32

        val TYPED_INDICES = intArrayOf(Index.WORLD_MAP_AREAS, Index.WORLD_MAP_LABELS, FontMetricsDecoder.INDEX)
    }
}
