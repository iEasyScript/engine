package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.source.codec.cs2.Cs2SourceCodec
import world.gregs.voidps.cache.store.CacheStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The clientscript index packed the way a server packs it: through [Packer], on its thread pool.
 *
 * A server builds its cache from the tree on startup, and it does that from inside
 * `Cache.get()` - so for the whole build the process has no cache of its own and the monitor that
 * would produce one is already held. A codec that reaches for the process cache from a packing
 * worker therefore waits on the build that is waiting on it, and the whole thing stops dead with
 * no output and no error. That is what this proves cannot happen: the pack runs parallel, under a
 * timeout, and the process must still have no cache when it finishes.
 *
 * A single-threaded pack would not catch it, and neither would a run that had already initialised
 * the process cache, so this test does neither.
 */
class ClientScriptParallelPackParityTest {

    private val scratches = ArrayList<Path>()

    @AfterTest
    fun cleanUp() {
        Cs2SourceCodec.reset()
        SourceCodecs.reset()
        for (directory in scratches) {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `the clientscript index packs in parallel without the process cache`() {
        val packed = CacheFixture.resolveCacheDir()
        assumeTrue(packed != null, "no game cache on this machine - skipping")
        assumeTrue(!Cache.initialised, "another test already opened the process cache - skipping")

        SourceCodecs.reset()
        Cs2SourceCodec.cachePath = packed
        val treeDirectory = scratch("tree")
        val output = scratch("packed")

        CacheStore.open(packed!!).use { store ->
            val created = SourceTree.create(treeDirectory, REVISION, store.kind, store.indexCount())
            val unpacked = Unpacker.unpack(store, created, UnpackOptions(indices = setOf(Index.CLIENT_SCRIPTS)))
            assertTrue(unpacked.archives > 0, "index ${Index.CLIENT_SCRIPTS} unpacked nothing")
            assertEquals(0, unpacked.inexact, "no clientscript should have needed a pristine fallback")
        }

        val result = Packer.pack(SourceTree.open(treeDirectory), output)
        assertEquals(1, result.indices)

        assertFalse(
            Cache.initialised,
            "packing opened the process cache; a server does that from inside Cache.get() and would deadlock"
        )

        // The reference table is what the client can ask for; a container the table never lists is
        // not part of the index and the tree does not hold one.
        val archives = SourceTree.open(treeDirectory).metadata(Index.CLIENT_SCRIPTS).archiveIds()
        CacheStore.open(packed).use { source ->
            CacheStore.open(output).use { built ->
                assertContentEquals(archives, built.archives(Index.CLIENT_SCRIPTS))
                for (archive in archives) {
                    assertContentEquals(
                        source.read(Index.CLIENT_SCRIPTS, archive),
                        built.read(Index.CLIENT_SCRIPTS, archive),
                        "clientscript $archive"
                    )
                }
                assertContentEquals(
                    source.readTable(Index.CLIENT_SCRIPTS),
                    built.readTable(Index.CLIENT_SCRIPTS)
                )
            }
        }
    }

    private fun scratch(name: String): Path =
        Files.createTempDirectory("cs2-parallel-$name").also { scratches.add(it) }

    private companion object {
        const val REVISION = CacheEra.NXT_REVISION
    }
}
