package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodec
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.VerbatimGroupCodec
import world.gregs.voidps.cache.source.codec.image.TextureImageCodec
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
import kotlin.test.assertTrue

/**
 * Every archive of the four texture indices, unpacked to real files and packed back, against the
 * group the cache holds.
 *
 * Skipped where this machine has no cache; the indices are eleven gigabytes decompressed, so an
 * archive's files are deleted the moment they have been packed back and only the counts are kept.
 */
class TextureCodecParityTest {

    private class Parity(val index: Int) {
        val archives = AtomicInteger()
        val images = AtomicInteger()
        val verbatim = AtomicInteger()
        val mismatched = AtomicInteger()

        override fun toString(): String =
            "index $index: ${archives.get()} archives, ${images.get()} exact as images, " +
                "${verbatim.get()} kept whole, ${mismatched.get()} mismatched"
    }

    @Test
    fun `texture archives pack back byte for byte`() {
        val cache = CacheFixture.resolveCacheDir()
        assumeTrue(cache != null, "no game cache on this machine — skipping")
        val tree = Files.createTempDirectory("texture-parity")
        SourceCodecs.context = SourceContext.none(CacheEra.NXT_REVISION)
        val reports = ArrayList<Parity>(INDICES.size)
        SqliteStore.open(cache!!, writable = false).use { store ->
            for (index in INDICES) {
                val directory = Files.createDirectories(tree.resolve(index.toString()))
                reports.add(measure(store, directory, index))
            }
        }
        for (report in reports) {
            println(report)
        }
        for (report in reports) {
            assertEquals(0, report.mismatched.get(), report.toString())
            assertEquals(0, report.verbatim.get(), report.toString())
        }
    }

    private fun measure(store: CacheStore, directory: Path, index: Int): Parity {
        val codec = SourceCodecs.codec(index)
        assertTrue(codec is TextureImageCodec, "index $index is registered to ${codec.id}")
        val table = ReferenceTable.decode(Container.decode(store.readTable(index)!!, trailer = false).data())
        val parity = Parity(index)
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            val futures = table.archives.map { entry ->
                pool.submit { archive(store, directory, index, entry, codec, parity) }
            }
            for (future in futures) {
                future.get()
            }
        } finally {
            pool.shutdown()
        }
        return parity
    }

    private fun archive(
        store: CacheStore,
        directory: Path,
        index: Int,
        entry: ArchiveEntry,
        codec: SourceCodec,
        parity: Parity
    ) {
        val bytes = store.read(index, entry.id) ?: return
        parity.archives.incrementAndGet()
        val group = Container.decode(bytes, null, store.trailers).data()
        val metadata = ArchiveMetadata(version = entry.version, nameHash = entry.nameHash)
        metadata.fileIds(entry.fileIds)
        val split = ArchiveGroup.split(group, entry.fileIds)
        val source = SourceArchive(index, entry.id, entry.nameHash, entry.fileIds, group, split.files, split.chunks, metadata)
        val unpacked = codec.unpack(directory, source)
        if (unpacked.files.any { it.path.endsWith(VerbatimGroupCodec.EXTENSION) }) {
            parity.verbatim.incrementAndGet()
        } else {
            parity.images.incrementAndGet()
        }
        for (file in unpacked.files) {
            SourceFiles.write(directory.resolve(file.path), file.bytes)
        }
        if (!codec.pack(directory, entry.id, metadata).group.contentEquals(group)) {
            parity.mismatched.incrementAndGet()
        }
        for (path in codec.files(directory, entry.id)) {
            SourceFiles.delete(path, directory)
        }
    }

    private companion object {
        val INDICES = intArrayOf(Index.TEXTURES_DXT, Index.TEXTURES_PNG, Index.TEXTURES_PNG_MIPPED, Index.TEXTURES_ETC)
    }
}
