package world.gregs.voidps.cache.source

import org.junit.jupiter.api.Assumptions.assumeTrue
import world.gregs.voidps.cache.CacheFixture
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.source.codec.SourceCodecs
import world.gregs.voidps.cache.store.CacheStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The source tree against the real cache: unpack it, pack the tree back, and demand that every
 * container and every reference table comes out byte for byte as it went in.
 *
 * This is the parity guarantee `docs/cache/CACHE_SOURCE_TREE.md` makes, and the only place it can
 * actually be proved. It also checks the two properties everything downstream leans on: unpacking
 * the same cache twice writes nothing the second time, and building an unchanged tree is a no-op;
 * then it edits one file and rebuilds, which is the whole reason the tree exists.
 *
 * By default the test covers the small indices so it stays quick; `-Dcache.source.whole=true` runs
 * it over everything but the audio streams. Skipped when there is no cache on the machine.
 */
class WholeCacheSourceTest {

    @Test
    fun `the cache round trips through a source tree`() {
        val packed = CacheFixture.resolveCacheDir()
        assumeTrue(packed != null, "no game cache on this machine - skipping")
        SourceCodecs.reset()
        val whole = System.getProperty("cache.source.whole").toBoolean()
        val work = Files.createTempDirectory("cache-source")
        val treeDirectory = work.resolve("tree")
        val output = work.resolve("packed")
        try {
            val store = CacheStore.open(packed!!)
            val passthrough = setOf(Index.AUDIO_STREAMS, Index.VORBIS)
            val indices = if (whole) null else SMALL
            val tree = SourceTree.create(treeDirectory, REVISION, store.kind, store.indexCount(), passthrough)
            val options = UnpackOptions(indices = indices, passthrough = passthrough, gamevals = GAMEVALS)
            val unpacked = try {
                Unpacker.unpack(store, tree, options) { println("  unpack $it") }
            } finally {
                store.close()
            }
            println("unpack: $unpacked")
            assertEquals(0, unpacked.missing, "every table entry should have a container")
            assertEquals(0, unpacked.inexact, "no archive should have needed a pristine fallback")
            assertEquals(0, unpacked.payloads, "every compressed payload should be reproducible")
            assertEquals(0, unpacked.opaque, "nothing should be opaque")
            assertEquals(0, unpacked.verbatim, "every chunked group should have a codec that keeps its split")

            val source = CacheStore.open(packed)
            try {
                val result = Packer.pack(SourceTree.open(treeDirectory), output, source)
                println("pack: $result")
                assertEquals(unpacked.archives + passthroughArchives(source, passthrough), result.archives)

                val report = Verifier.verify(SourceTree.open(treeDirectory), source) { println("  verify $it") }
                println("verify: $report")
                assertTrue(report.parity, "the tree must reproduce the cache exactly: $report")
                assertEquals(unpacked.archives, report.identical)

                val rebuilt = CacheStore.open(output)
                try {
                    val again = Verifier.verify(SourceTree.open(treeDirectory), rebuilt)
                    assertTrue(again.parity, "the packed cache must be what the tree says: $again")
                } finally {
                    rebuilt.close()
                }

                val build = Builder.build(SourceTree.open(treeDirectory), output, source) { println("  build $it") }
                println("no-op build: $build")
                assertTrue(build.unchanged, "an unchanged tree must rebuild nothing")
                assertEquals(0, build.hashedFiles, "an unchanged tree must not be rehashed")

                val stamps = timestamps(treeDirectory)
                val second = Unpacker.unpack(source, SourceTree.open(treeDirectory), options)
                println("re-unpack: $second")
                assertEquals(0, second.written, "re-unpacking an unchanged cache must rewrite nothing")
                assertEquals(0, second.deleted)
                assertEquals(stamps, timestamps(treeDirectory), "not a single file may have been touched")

                edit(SourceTree.open(treeDirectory), output, source)
            } finally {
                source.close()
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /**
     * Change one raw file, rebuild, and check exactly that archive moved and nothing else did. The
     * dead config archives are the raw files left in the tree: a byte appended to one of them is
     * an edit the builder has to notice and nothing else has to understand.
     */
    private fun edit(tree: SourceTree, output: Path, source: CacheStore) {
        val index = Index.CONFIGS
        val directory = tree.indexDirectory(index).resolve("archive_$RAW_CONFIG_ARCHIVE")
        val file = Files.list(directory).use { stream -> stream.filter { it.name.endsWith(".dat") }.findFirst().get() }
        val original = Files.readAllBytes(file)
        Files.write(file, original + byteArrayOf(0))
        val build = Builder.build(tree, output, source) { println("  edit build $it") }
        println("edit build: $build")
        assertEquals(1, build.archives, "exactly one archive should have been repacked")
        assertEquals(1, build.indices, "exactly one table should have been rewritten")
        CacheStore.open(output).use { store ->
            val archive = RAW_CONFIG_ARCHIVE
            val stored = store.read(index, archive)!!
            val shipped = source.read(index, archive)!!
            assertTrue(!stored.contentEquals(shipped), "the edited archive must differ from the shipped one")
        }
        Files.write(file, original)
        val restored = Builder.build(tree, output, source)
        assertEquals(1, restored.archives)
        val report = Verifier.verify(tree, source)
        assertTrue(report.parity, "restoring the file must restore parity: $report")
    }

    private fun passthroughArchives(store: CacheStore, passthrough: Set<Int>): Int =
        passthrough.sumOf { store.archives(it).size }

    private fun timestamps(root: Path): Map<String, FileTime> {
        val stamps = HashMap<String, FileTime>()
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (Files.isRegularFile(path)) {
                    stamps[root.relativize(path).toString()] = Files.getLastModifiedTime(path)
                }
            }
        }
        return stamps
    }

    private companion object {
        const val REVISION = 949

        /** A config archive the client never reads, whose files are single terminator bytes. */
        const val RAW_CONFIG_ARCHIVE = 2

        val GAMEVALS: Path = Path.of("re-resources/gamevals")

        /** The definition and record indices: quick, and where every typed codec lives. */
        val SMALL = setOf(2, 3, 10, 13, 16, 17, 18, 19, 20, 21, 22, 23, 24, 26, 27, 28, 29, 32, 33, 34, 35, 41, 42, 49, 57, 58, 59, 60, 61, 62, 65, 66)
    }
}
