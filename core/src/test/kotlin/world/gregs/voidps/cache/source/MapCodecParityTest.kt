package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.MapSquare
import world.gregs.voidps.cache.source.codec.MapCodec
import world.gregs.voidps.cache.source.codec.SourceArchive
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.ArchiveEntry
import world.gregs.voidps.cache.store.ArchiveGroup
import world.gregs.voidps.cache.store.CacheStore
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.ReferenceTable
import world.gregs.voidps.cache.store.SqliteStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every archive of the map index, unpacked to real files and packed back, against the group the
 * cache holds.
 *
 * Skipped where this machine has no cache. The index is a gigabyte of JSON, so an archive's files
 * are deleted the moment they have been packed back and only the counts are kept.
 */
class MapCodecParityTest {

    private class Kind {
        val files = AtomicInteger()
        val exact = AtomicInteger()
        val pristine = AtomicInteger()
    }

    @Test
    fun `map archives pack back byte for byte`() {
        val cache = CacheFixture.resolveCacheDir()
        assumeTrue(cache != null, "no game cache on this machine — skipping")
        val tree = Files.createTempDirectory("map-parity")
        SourceCodecs.context = SourceContext.none(CacheEra.NXT_REVISION)
        val directory = Files.createDirectories(tree.resolve(Index.MAPS.toString()))
        val kinds = ConcurrentHashMap<Int, Kind>()
        val archives = AtomicInteger()
        val mismatched = AtomicInteger()
        SqliteStore.open(cache!!, writable = false).use { store ->
            val table = ReferenceTable.decode(Container.decode(store.readTable(Index.MAPS)!!, trailer = false).data())
            val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
            try {
                val futures = table.archives.map { entry ->
                    pool.submit { archive(store, directory, entry, kinds, archives, mismatched) }
                }
                for (future in futures) {
                    future.get()
                }
            } finally {
                pool.shutdown()
            }
        }
        println("index ${Index.MAPS}: ${archives.get()} archives, ${mismatched.get()} mismatched")
        for (file in MapSquare.File.entries) {
            val kind = kinds[file.id] ?: continue
            println(
                "  ${file.file}: ${kind.files.get()} files, ${kind.exact.get()} exact, " +
                    "${kind.pristine.get()} pristine"
            )
        }
        assertEquals(0, mismatched.get(), "archives that did not pack back byte for byte")
    }

    private fun archive(
        store: CacheStore,
        directory: Path,
        entry: ArchiveEntry,
        kinds: ConcurrentHashMap<Int, Kind>,
        archives: AtomicInteger,
        mismatched: AtomicInteger
    ) {
        val bytes = store.read(Index.MAPS, entry.id) ?: return
        archives.incrementAndGet()
        val group = Container.decode(bytes, null, store.trailers).data()
        val metadata = ArchiveMetadata(version = entry.version, nameHash = entry.nameHash)
        val split = ArchiveGroup.split(group, entry.fileIds)
        metadata.layout = split.layout
        metadata.layoutVersion = split.version
        val source = SourceArchive(
            Index.MAPS, entry.id, entry.nameHash, entry.fileIds, group, split.files, split.chunks, metadata
        )
        val unpacked = MapCodec.unpack(directory, source)
        metadata.pristine = unpacked.pristine
        for (file in unpacked.files) {
            SourceFiles.write(directory.resolve(file.path), file.bytes)
        }
        for (id in entry.fileIds) {
            val kind = kinds.computeIfAbsent(id) { Kind() }
            kind.files.incrementAndGet()
            if (unpacked.pristine.containsKey(id)) kind.pristine.incrementAndGet() else kind.exact.incrementAndGet()
        }
        if (!MapCodec.pack(directory, entry.id, metadata).group.contentEquals(group)) {
            mismatched.incrementAndGet()
        }
        for (path in MapCodec.files(directory, entry.id)) {
            SourceFiles.delete(path, directory)
        }
    }
}
